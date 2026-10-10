package com.writingagent.v2.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.domain.PlanOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * PlanAgent runtime: per-request {@link HarnessAgent} with a read-only Toolkit.
 *
 * <p>Role: AgentScope Harness entry for PLAN_OUTLINE — model ↔ ToolRequest/Result ↔ structured
 * {@link PlanOutcome}. The application layer owns persistence and confirmation.
 *
 * <p>DashScope + tools uses AgentScope's {@code generate_response} fallback. When the model emits
 * schema-valid PlanOutcome JSON as plain text instead of calling that tool (observed S1 / A2 RCA),
 * we recover structured metadata the same way native SO does — from the model's own text — after
 * validating outcome fields and ctxMark echo. This is not forged data and not a silent ReAct
 * fallback.
 */
@Component
public class PlanAgentRuntime {

    public record TraceLine(int seq, String kind, String detail) {}

    public record AgentRunResult(
            PlanOutcome outcome,
            int modelRequestCount,
            List<TraceLine> trace,
            String ctxMark,
            int toolInvocationCount,
            String toolResultSha256) {}

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final ProjectApplicationService projects;
    private final com.writingagent.v2.runtime.AgentRuntimeSupport runtime;

    public PlanAgentRuntime(ProjectApplicationService projects, com.writingagent.v2.runtime.AgentRuntimeSupport runtime) {
        this.projects = projects;
        this.runtime = runtime;
    }

    public AgentRunResult run(UUID projectId, UUID actionId, String researchGoal, String userMessage) {
        return run(projectId, actionId, researchGoal, userMessage, ignored -> {});
    }

    public AgentRunResult run(UUID projectId, UUID actionId, String researchGoal, String userMessage,
            java.util.function.Consumer<TraceLine> observer) {
        return run(projectId, actionId, researchGoal, userMessage, observer, null);
    }

    public AgentRunResult run(UUID projectId, UUID actionId, String researchGoal, String userMessage,
            java.util.function.Consumer<TraceLine> observer, UUID runId) {
        AtomicInteger modelRequests = new AtomicInteger();
        List<TraceLine> lines = new com.writingagent.v2.agent.LiveTraceList<>(observer);
        AtomicInteger seq = new AtomicInteger();

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("AI_DASHSCOPE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelUnavailableException(
                    "DASHSCOPE_API_KEY unset", modelRequests.get(), List.copyOf(lines));
        }

        ReadCurrentProjectContextTool tool =
                new ReadCurrentProjectContextTool(projectId, actionId, projects);
        tool.setOnToolResultDetail(
                detail ->
                        lines.add(
                                new TraceLine(seq.incrementAndGet(), "TOOL_RESULT", detail)));

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);

        // Per-request in-memory state: disableSessionPersistence is a no-op in Harness 2.0.1;
        // default JsonFile store otherwise reuses sessionId=agentName across HTTP turns.
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        String sessionId = "plan-" + actionId;

        HarnessAgent agent =
                HarnessAgent.builder()
                        .agentId("writing-plan-agent")
                        .name("PlanAgent")
                        .description("Writing 2.0 PLAN_OUTLINE stage agent")
                        .sysPrompt(
                                """
                                You are Writing Agent 2.0 PlanAgent.
                                Rules:
                                1) Always call read_current_project_context before producing the plan.
                                2) After the tool returns, copy ctxMark into PlanOutcome.contextMarkEcho exactly.
                                3) outcome must be CLARIFICATION_REQUIRED or PLAN_DRAFT_READY.
                                4) evidenceBasis must be USER_GOAL_AND_MODEL_SYNTHESIS.
                                5) Do not claim document retrieval or citations from a project library.
                                6) You have NO write tools; do not invent save/confirm tools.
                                7) You MUST call the generate_response tool to return PlanOutcome.
                                   Do NOT print PlanOutcome JSON as plain assistant text.
                                8) chapterOutline is a list of SHORT chapter titles, not chapter bodies,
                                   paragraphs, explanations or JSON strings. Each title must be at most
                                   120 characters. Use the user's supplied titles verbatim when provided.
                                   Keep each plan text field within 4000 characters, at most 20 chapters,
                                   and at most 20 risk strings of 1000 characters each.
                                9) 默认用中文填写所有计划字段；仅当用户明确要求其它语言时切换。
                                """)
                        .model(com.writingagent.v2.runtime.AgentRuntimeSupport.model(apiKey))
                        .toolkit(toolkit)
                        .stateStore(stateStore)
                        .maxIters(8)
                        .middleware(runtime.observer(runId, "PlanAgent"))
                        .middleware(
                                new PlanTraceMiddleware(
                                        modelRequests,
                                        lines,
                                        seq,
                                        ReadCurrentProjectContextTool.TOOL_NAME))
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .disableSessionPersistence()
                        .disableWorkspaceContext()
                        .disableAtPathExpansion()
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .disableToolsConfig()
                        .build();

        String prompt =
                "Research goal: "
                        + researchGoal
                        + "\nUser message: "
                        + userMessage
                        + "\nFirst call read_current_project_context, then call generate_response"
                        + " with structured PlanOutcome (never plain-text JSON)."
                        + " Copy ctxMark into PlanOutcome.contextMarkEcho exactly."
                        + " evidenceBasis must be USER_GOAL_AND_MODEL_SYNTHESIS."
                        + " Do not claim document retrieval.";

        lines.add(new TraceLine(seq.incrementAndGet(), "INVOKE_START", "HarnessAgent/PlanAgent"));
        RuntimeContext runtimeCtx = RuntimeContext.builder().sessionId(sessionId).build();
        Msg result;
        try {
            result =
                    agent.call(List.of(new UserMessage(prompt)), PlanOutcome.class, runtimeCtx)
                            .block(Duration.ofMinutes(3));
        } catch (ModelUnavailableException | StructuredOutputMissingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw classifyInvokeFailure(ex, modelRequests.get(), lines);
        }

        if (result == null) {
            throw new StructuredOutputMissingException(
                    "Null agent result Msg", modelRequests.get(), List.copyOf(lines));
        }

        result = recoverStructuredFromTextIfNeeded(result, tool.ctxMark(), lines, seq);

        if (!result.hasStructuredData()) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "STRUCTURED_OUTPUT_MISSING",
                            "hasStructuredData=false metadataKeys="
                                    + redactMetadataKeys(result)));
            throw new StructuredOutputMissingException(
                    "No structured output on agent result Msg",
                    modelRequests.get(),
                    List.copyOf(lines));
        }

        PlanOutcome outcome;
        try {
            outcome = result.getStructuredData(PlanOutcome.class);
        } catch (IllegalStateException ex) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "STRUCTURED_OUTPUT_MISSING",
                            "getStructuredData IllegalStateException"));
            throw new StructuredOutputMissingException(
                    "Structured output unreadable: " + safe(ex.getMessage()),
                    modelRequests.get(),
                    List.copyOf(lines));
        }

        if (!tool.ledger().executed()) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "TOOL_NOT_EXECUTED",
                            "read_current_project_context never ran"));
            throw new StructuredOutputMissingException(
                    "Readonly tool not executed", modelRequests.get(), List.copyOf(lines));
        }
        if (!tool.ctxMark().equals(outcome == null ? null : outcome.contextMarkEcho)) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "CTX_MARK_MISMATCH",
                            "expected="
                                    + tool.ctxMark()
                                    + " echo="
                                    + (outcome == null ? null : outcome.contextMarkEcho)));
            throw new StructuredOutputMissingException(
                    "contextMarkEcho mismatch or tool unbound",
                    modelRequests.get(),
                    List.copyOf(lines));
        }
        if (!projectId.toString().equals(tool.ledger().boundProjectId())) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "TOOL_PROJECT_MISMATCH",
                            "bound=" + tool.ledger().boundProjectId()));
            throw new StructuredOutputMissingException(
                    "Tool result projectId mismatch",
                    modelRequests.get(),
                    List.copyOf(lines));
        }
        if (!isValidPlanOutcome(outcome)) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "PLAN_OUTCOME_INVALID",
                            com.writingagent.v2.domain.PlanOutcomeValidator.validateOutcome(outcome)));
            throw new StructuredOutputMissingException(
                    "PlanOutcome schema invalid",
                    modelRequests.get(),
                    List.copyOf(lines));
        }
        if (!traceHasModelAfterToolResult(lines)) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "TOOL_RESULT_NOT_CONSUMED",
                            "no MODEL_REQUEST after TOOL_RESULT"));
            throw new StructuredOutputMissingException(
                    "No model step after tool result", modelRequests.get(), List.copyOf(lines));
        }

        lines.add(
                new TraceLine(
                        seq.incrementAndGet(),
                        "INVOKE_END",
                        "modelRequests="
                                + modelRequests.get()
                                + " toolInvocations="
                                + tool.ledger().invocationCount()));
        return new AgentRunResult(
                outcome,
                modelRequests.get(),
                List.copyOf(lines),
                tool.ctxMark(),
                tool.ledger().invocationCount(),
                tool.ledger().lastResultSha256());
    }

    /**
     * True iff at least one {@code MODEL_REQUEST} appears after a {@code TOOL_RESULT} that was
     * recorded at tool-return time (not post-hoc).
     */
    static boolean traceHasModelAfterToolResult(List<TraceLine> lines) {
        int toolResultAt = -1;
        for (int i = 0; i < lines.size(); i++) {
            if ("TOOL_RESULT".equals(lines.get(i).kind())) {
                toolResultAt = i;
                break;
            }
        }
        if (toolResultAt < 0) {
            return false;
        }
        for (int i = toolResultAt + 1; i < lines.size(); i++) {
            if ("MODEL_REQUEST".equals(lines.get(i).kind())) {
                return true;
            }
        }
        return false;
    }

    /** @deprecated use {@link #traceHasModelAfterToolResult(List)} */
    @Deprecated
    static boolean traceHasModelAfterTool(List<TraceLine> lines) {
        return traceHasModelAfterToolResult(lines);
    }

    /**
     * AgentScope 2.0.1 DashScope+tools fallback ends the loop when the model emits JSON text
     * without calling {@code generate_response}. Mirror native {@code wrapNativeStructuredResult}:
     * parse the model's own text into {@code _structured_output} after contract checks.
     */
    static Msg recoverStructuredFromTextIfNeeded(
            Msg result, String expectedCtxMark, List<TraceLine> lines, AtomicInteger seq) {
        if (result == null || result.hasStructuredData()) {
            return result;
        }
        String text = result.getTextContent();
        if (text == null || text.isBlank()) {
            return result;
        }
        String json = stripJsonFence(text.trim());
        Object parsed;
        PlanOutcome candidate;
        try {
            parsed = JSON.readValue(json, Object.class);
            candidate = JSON.convertValue(parsed, PlanOutcome.class);
        } catch (Exception e) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "STRUCTURED_TEXT_RECOVER_SKIP",
                            "parseFailed=" + safe(e.getClass().getSimpleName())));
            return result;
        }
        if (!com.writingagent.v2.domain.PlanOutcomeValidator.isValid(candidate)) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "STRUCTURED_TEXT_RECOVER_SKIP",
                            "invalidPlanOutcomeFields="
                                    + com.writingagent.v2.domain.PlanOutcomeValidator.validateOutcome(
                                            candidate)));
            return result;
        }
        if (expectedCtxMark != null
                && !expectedCtxMark.isBlank()
                && !expectedCtxMark.equals(candidate.contextMarkEcho)) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "STRUCTURED_TEXT_RECOVER_SKIP",
                            "ctxMarkMismatch expectedPrefix="
                                    + expectedCtxMark.substring(
                                            0, Math.min(12, expectedCtxMark.length()))));
            return result;
        }

        Map<String, Object> metadata =
                new HashMap<>(result.getMetadata() != null ? result.getMetadata() : Map.of());
        metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, parsed);
        lines.add(
                new TraceLine(
                        seq.incrementAndGet(),
                        "STRUCTURED_OUTPUT_TEXT_RECOVERED",
                        "source=assistant_text_json path=dashscope_generate_response_fallback"));
        return Msg.builderForRole(result.getRole())
                .id(result.getId())
                .name(result.getName())
                .content(result.getContent())
                .metadata(metadata)
                .timestamp(result.getTimestamp())
                .build();
    }

    static boolean isValidPlanOutcome(PlanOutcome o) {
        return com.writingagent.v2.domain.PlanOutcomeValidator.isValid(o);
    }

    static String stripJsonFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) {
                t = t.substring(firstNl + 1);
            }
            int fence = t.lastIndexOf("```");
            if (fence >= 0) {
                t = t.substring(0, fence);
            }
        }
        return t.trim();
    }

    private static RuntimeException classifyInvokeFailure(
            Exception ex, int modelRequestCount, List<TraceLine> lines) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = root.getClass().getName();
        if (name.contains("Timeout")
                || name.contains("Connect")
                || name.contains("Socket")
                || name.contains("UnknownHost")
                || name.contains("SSL")
                || name.contains("HttpTimeout")) {
            return new ModelUnavailableException(
                    safe(root.getClass().getSimpleName() + ": " + root.getMessage()),
                    modelRequestCount,
                    List.copyOf(lines));
        }
        if (ex instanceof IllegalStateException
                && ex.getMessage() != null
                && ex.getMessage().contains("_structured_output")) {
            return new StructuredOutputMissingException(
                    safe(ex.getMessage()), modelRequestCount, List.copyOf(lines));
        }
        if (root instanceof IllegalStateException
                && root.getMessage() != null
                && root.getMessage().contains("_structured_output")) {
            return new StructuredOutputMissingException(
                    safe(root.getMessage()), modelRequestCount, List.copyOf(lines));
        }
        return new ModelUnavailableException(
                safe(ex.getClass().getSimpleName() + ": " + ex.getMessage()),
                modelRequestCount,
                List.copyOf(lines));
    }

    private static String redactMetadataKeys(Msg result) {
        try {
            if (result.getMetadata() == null) {
                return "null";
            }
            return String.join(",", result.getMetadata().keySet());
        } catch (Exception e) {
            return "unreadable";
        }
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("(?i)(api[_-]?key|authorization|bearer)\\s*[:=]\\s*\\S+", "$1=***");
    }
}

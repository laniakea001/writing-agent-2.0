package com.writingagent.v2.agent.chapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.agent.plan.ModelUnavailableException;
import com.writingagent.v2.agent.plan.StructuredOutputMissingException;
import com.writingagent.v2.domain.ChapterOutcome;
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

/** Per-request HarnessAgent for one chapter draft (ADR-002). */
@Component
public class ChapterAgentRuntime {

    public record TraceLine(int seq, String kind, String detail) {}

    public record AgentRunResult(
            ChapterOutcome outcome,
            int modelRequestCount,
            List<TraceLine> trace,
            String ctxMark,
            int toolInvocationCount,
            String toolResultSha256) {}

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final com.writingagent.v2.runtime.AgentRuntimeSupport runtime;
    public ChapterAgentRuntime(com.writingagent.v2.runtime.AgentRuntimeSupport runtime) { this.runtime = runtime; }

    public AgentRunResult run(
            UUID projectId,
            UUID actionId,
            UUID articleId,
            int chapterIndex,
            String chapterTitle,
            String researchGoal,
            List<String> outline,
            List<ReadConfirmedOutlineTool.PriorChapter> priorChapters) {
        return run(
                projectId,
                actionId,
                articleId,
                chapterIndex,
                chapterTitle,
                researchGoal,
                outline,
                priorChapters,
                null,
                null);
    }

    /**
     * @param userInstruction optional user rewrite/edit guidance; must be applied when non-blank
     * @param existingBody optional current chapter body when rewriting
     */
    public AgentRunResult run(
            UUID projectId,
            UUID actionId,
            UUID articleId,
            int chapterIndex,
            String chapterTitle,
            String researchGoal,
            List<String> outline,
            List<ReadConfirmedOutlineTool.PriorChapter> priorChapters,
            String userInstruction,
            String existingBody) {
        return run(projectId, actionId, articleId, chapterIndex, chapterTitle, researchGoal,
                outline, priorChapters, userInstruction, existingBody, ignored -> {});
    }

    public AgentRunResult run(UUID projectId, UUID actionId, UUID articleId, int chapterIndex,
            String chapterTitle, String researchGoal, List<String> outline,
            List<ReadConfirmedOutlineTool.PriorChapter> priorChapters,
            String userInstruction, String existingBody,
            java.util.function.Consumer<TraceLine> observer) {
        return run(projectId, actionId, articleId, chapterIndex, chapterTitle, researchGoal,
                outline, priorChapters, userInstruction, existingBody, observer, null);
    }

    public AgentRunResult run(UUID projectId, UUID actionId, UUID articleId, int chapterIndex,
            String chapterTitle, String researchGoal, List<String> outline,
            List<ReadConfirmedOutlineTool.PriorChapter> priorChapters,
            String userInstruction, String existingBody,
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
                    "DASHSCOPE_API_KEY unset", modelRequests.get(), toPlanTrace(lines));
        }

        ReadConfirmedOutlineTool tool =
                new ReadConfirmedOutlineTool(
                        projectId,
                        actionId,
                        articleId,
                        chapterIndex,
                        chapterTitle,
                        researchGoal,
                        outline,
                        priorChapters);
        tool.setOnToolResultDetail(
                detail ->
                        lines.add(
                                new TraceLine(seq.incrementAndGet(), "TOOL_RESULT", detail)));

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);

        boolean rewrite = userInstruction != null && !userInstruction.isBlank();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .agentId("writing-chapter-agent")
                        .name("ChapterAgent")
                        .description("Writing 2.0 chapter draft agent")
                        .sysPrompt(
                                """
                                You are Writing Agent 2.0 ChapterAgent.
                                Rules:
                                1) Always call read_confirmed_outline_context first.
                                2) Copy ctxMark into ChapterOutcome.contextMarkEcho exactly.
                                3) outcome must be CHAPTER_DRAFT_READY.
                                4) evidenceBasis must be USER_GOAL_AND_MODEL_SYNTHESIS.
                                5) Write bodyMarkdown for ONLY the target chapterIndex; Chinese prose, non-empty.
                                6) Do not claim document retrieval or external citations.
                                7) MUST call generate_response with ChapterOutcome; never plain-text JSON.
                                8) When the user provides rewrite instructions, you MUST follow them
                                   literally (including any required marker phrases) while keeping the chapter coherent.
                                """)
                        .model(com.writingagent.v2.runtime.AgentRuntimeSupport.model(apiKey))
                        .toolkit(toolkit)
                        .stateStore(new InMemoryAgentStateStore())
                        .maxIters(8)
                        .middleware(runtime.observer(runId, "ChapterAgent"))
                        .middleware(new ChapterTraceMiddleware(modelRequests, lines, seq))
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

        StringBuilder prompt = new StringBuilder();
        if (rewrite) {
            prompt.append("REWRITE mode for chapter ")
                    .append(chapterIndex)
                    .append(" titled \"")
                    .append(chapterTitle)
                    .append("\".\n");
            prompt.append("User rewrite instruction (MUST follow):\n")
                    .append(userInstruction.trim())
                    .append("\n");
            if (existingBody != null && !existingBody.isBlank()) {
                String clip =
                        existingBody.length() > 6000
                                ? existingBody.substring(0, 6000) + "…"
                                : existingBody;
                prompt.append("Current chapter body to revise:\n---\n")
                        .append(clip)
                        .append("\n---\n");
            }
            prompt.append(
                    "Call read_confirmed_outline_context, then generate_response with revised"
                            + " ChapterOutcome (same chapterIndex). Apply the user instruction"
                            + " completely.");
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "REWRITE_INSTRUCTION_BOUND",
                            "chars=" + userInstruction.trim().length()));
        } else {
            prompt.append("Write chapter ")
                    .append(chapterIndex)
                    .append(" titled \"")
                    .append(chapterTitle)
                    .append("\". Call read_confirmed_outline_context, then generate_response")
                    .append(" with ChapterOutcome (chapterIndex=")
                    .append(chapterIndex)
                    .append("). bodyMarkdown must be substantial Chinese prose, not a stub.");
        }

        lines.add(new TraceLine(seq.incrementAndGet(), "INVOKE_START", "HarnessAgent/ChapterAgent"));
        RuntimeContext runtimeCtx =
                RuntimeContext.builder().sessionId("chapter-" + actionId + "-" + chapterIndex).build();
        Msg result;
        try {
            result =
                    agent.call(
                                    List.of(new UserMessage(prompt.toString())),
                                    ChapterOutcome.class,
                                    runtimeCtx)
                            .block(Duration.ofMinutes(4));
        } catch (ModelUnavailableException | StructuredOutputMissingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw classify(ex, modelRequests.get(), lines);
        }

        if (result == null) {
            throw new StructuredOutputMissingException(
                    "Null chapter agent Msg", modelRequests.get(), toPlanTrace(lines));
        }
        result = recoverStructuredFromTextIfNeeded(result, tool.ctxMark(), chapterIndex, lines, seq);
        // A text-only answer is not a candidate and must never be saved as one.
        // Permit one format-only repair in this same isolated invocation. The normal
        // tool-ledger and context checks below still apply; no business write is retried.
        if (!result.hasStructuredData() && tool.ledger().executed()
                && traceHasModelAfterToolResult(lines)) {
            lines.add(new TraceLine(seq.incrementAndGet(), "STRUCTURED_FORMAT_REPAIR", "attempt=1"));
            try {
                result = agent.call(List.of(new UserMessage(
                        "Your last response was not a ChapterOutcome. Do not write to storage. "
                        + "Return the revised target chapter ONLY by calling generate_response. "
                        + "outcome=CHAPTER_DRAFT_READY, evidenceBasis=USER_GOAL_AND_MODEL_SYNTHESIS, "
                        + "chapterIndex=" + chapterIndex + ", contextMarkEcho=" + tool.ctxMark()
                        + ". Keep the original rewrite instruction exactly: " + (userInstruction == null ? "" : userInstruction))),
                        ChapterOutcome.class, runtimeCtx).block(Duration.ofMinutes(4));
            } catch (Exception ex) {
                throw classify(ex, modelRequests.get(), lines);
            }
            result = recoverStructuredFromTextIfNeeded(result, tool.ctxMark(), chapterIndex, lines, seq);
        }
        if (result == null || !result.hasStructuredData()) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "STRUCTURED_OUTPUT_MISSING",
                            "hasStructuredData=false"));
            throw new StructuredOutputMissingException(
                    "No structured output on chapter Msg",
                    modelRequests.get(),
                    toPlanTrace(lines));
        }

        ChapterOutcome outcome = result.getStructuredData(ChapterOutcome.class);
        if (!tool.ledger().executed()) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "TOOL_NOT_EXECUTED",
                            ReadConfirmedOutlineTool.TOOL_NAME + " never ran"));
            throw new StructuredOutputMissingException(
                    "Chapter readonly tool not executed",
                    modelRequests.get(),
                    toPlanTrace(lines));
        }
        if (outcome == null
                || !tool.ctxMark().equals(outcome.contextMarkEcho)
                || !projectId.toString().equals(tool.ledger().boundProjectId())) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "CTX_MARK_OR_PROJECT_MISMATCH",
                            "expectedCtx="
                                    + tool.ctxMark()
                                    + " echo="
                                    + (outcome == null ? null : outcome.contextMarkEcho)));
            throw new StructuredOutputMissingException(
                    "Chapter ctxMark/project mismatch",
                    modelRequests.get(),
                    toPlanTrace(lines));
        }
        if (!traceHasModelAfterToolResult(lines)) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "TOOL_RESULT_NOT_CONSUMED",
                            "no MODEL_REQUEST after TOOL_RESULT"));
            throw new StructuredOutputMissingException(
                    "No model step after chapter tool result",
                    modelRequests.get(),
                    toPlanTrace(lines));
        }
        try {
            com.writingagent.v2.domain.ChapterInstructionContract.validateText(userInstruction,outcome.bodyMarkdown,chapterIndex);
        } catch (com.writingagent.v2.domain.WritingRuleException mismatch) {
            if (!"REWRITE_REQUIREMENT_NOT_MET".equals(mismatch.code())) throw mismatch;
            lines.add(new TraceLine(seq.incrementAndGet(),"REWRITE_REQUIREMENT_REPAIR","attempt=1"));
            // One content correction, no storage retry. Application validates the candidate again.
            Msg repaired=agent.call(List.of(new UserMessage(
                    "上次候选未满足用户明确的结尾标记。请仅修正当前第"+chapterIndex+"章，不修改其它章节。"
                    +"必须严格遵循原始要求："+userInstruction
                    +"\n通过 generate_response 返回完整 ChapterOutcome；contextMarkEcho="+tool.ctxMark()
                    +"；chapterIndex="+chapterIndex+"；evidenceBasis=USER_GOAL_AND_MODEL_SYNTHESIS。")),
                    ChapterOutcome.class,runtimeCtx).block(Duration.ofMinutes(4));
            repaired=recoverStructuredFromTextIfNeeded(repaired,tool.ctxMark(),chapterIndex,lines,seq);
            if(repaired==null||!repaired.hasStructuredData())throw mismatch;
            ChapterOutcome corrected=repaired.getStructuredData(ChapterOutcome.class);
            if(corrected==null||!tool.ctxMark().equals(corrected.contextMarkEcho)||corrected.chapterIndex!=chapterIndex
                    ||!ChapterOutcome.SOURCE_BASIS.equals(corrected.evidenceBasis))throw mismatch;
            com.writingagent.v2.domain.ChapterInstructionContract.validateText(userInstruction,corrected.bodyMarkdown,chapterIndex);
            outcome=corrected;
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

    static Msg recoverStructuredFromTextIfNeeded(
            Msg result,
            String expectedCtxMark,
            int expectedChapterIndex,
            List<TraceLine> lines,
            AtomicInteger seq) {
        if (result == null || result.hasStructuredData()) {
            return result;
        }
        String text = result.getTextContent();
        if (text == null || text.isBlank()) {
            return result;
        }
        String json = stripJsonFence(text.trim());
        try {
            Object parsed = JSON.readValue(json, Object.class);
            ChapterOutcome candidate = JSON.convertValue(parsed, ChapterOutcome.class);
            if (!ChapterOutcome.CHAPTER_DRAFT_READY.equals(candidate.outcome)
                    || candidate.bodyMarkdown == null
                    || candidate.bodyMarkdown.isBlank()
                    || candidate.chapterIndex != expectedChapterIndex
                    || !ChapterOutcome.SOURCE_BASIS.equals(candidate.evidenceBasis)
                    || (expectedCtxMark != null
                            && !expectedCtxMark.equals(candidate.contextMarkEcho))) {
                lines.add(
                        new TraceLine(
                                seq.incrementAndGet(),
                                "STRUCTURED_TEXT_RECOVER_SKIP",
                                "chapterContractFailed"));
                return result;
            }
            Map<String, Object> metadata =
                    new HashMap<>(result.getMetadata() != null ? result.getMetadata() : Map.of());
            metadata.put(MessageMetadataKeys.STRUCTURED_OUTPUT, parsed);
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "STRUCTURED_OUTPUT_TEXT_RECOVERED",
                            "source=assistant_text_json"));
            return Msg.builderForRole(result.getRole())
                    .id(result.getId())
                    .name(result.getName())
                    .content(result.getContent())
                    .metadata(metadata)
                    .timestamp(result.getTimestamp())
                    .build();
        } catch (Exception e) {
            lines.add(
                    new TraceLine(
                            seq.incrementAndGet(),
                            "STRUCTURED_TEXT_RECOVER_SKIP",
                            e.getClass().getSimpleName()));
            return result;
        }
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

    private static RuntimeException classify(
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
                    toPlanTrace(lines));
        }
        return new ModelUnavailableException(
                safe(ex.getClass().getSimpleName() + ": " + ex.getMessage()),
                modelRequestCount,
                toPlanTrace(lines));
    }

    private static List<com.writingagent.v2.agent.plan.PlanAgentRuntime.TraceLine> toPlanTrace(
            List<TraceLine> lines) {
        return lines.stream()
                .map(
                        l ->
                                new com.writingagent.v2.agent.plan.PlanAgentRuntime.TraceLine(
                                        l.seq(), l.kind(), l.detail()))
                .toList();
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("(?i)(api[_-]?key|authorization|bearer)\\s*[:=]\\s*\\S+", "$1=***");
    }
}

package com.writingagent.v2.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.agent.main.MainAgentTurnContext;
import com.writingagent.v2.agent.main.WritingMainAgent;
import com.writingagent.v2.application.port.ActionLedgerPort;
import com.writingagent.v2.application.port.ConversationPort;
import com.writingagent.v2.domain.ActionStatus;
import com.writingagent.v2.domain.RunStatus;
import com.writingagent.v2.domain.TaskStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MainAgentTurnUseCase {

    public static final String OP = "ASSISTANT_TURN";

    private static final Logger log = LoggerFactory.getLogger(MainAgentTurnUseCase.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final ActionLedgerService actions;
    private final TaskRunService taskRuns;
    private final ConversationPort conversations;
    private final WritingMainAgent mainAgent;
    private final TransactionTemplate tx;
    private final InFlightRootActions inFlight;

    public MainAgentTurnUseCase(
            ActionLedgerService actions,
            TaskRunService taskRuns,
            ConversationPort conversations,
            WritingMainAgent mainAgent,
            TransactionTemplate tx,
            InFlightRootActions inFlight) {
        this.actions = actions;
        this.taskRuns = taskRuns;
        this.conversations = conversations;
        this.mainAgent = mainAgent;
        this.tx = tx;
        this.inFlight = inFlight;
    }

    public Map<String, Object> runTurn(
            UUID projectId, UUID conversationId, String clientActionId, String message) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId required");
        }
        String text = message == null ? "" : message;
        String digest =
                ActionLedgerService.digestFor(
                        projectId, conversationId, null, OP, text, null);
        String ck = InFlightRootActions.clientKey(projectId, clientActionId);
        Object lock = inFlight.lockForClientKey(ck);

        ActionLedgerService.ClaimResult claim;
        synchronized (lock) {
            claim = actions.claim(projectId, clientActionId, digest, OP);
            if (claim.kind() == ActionLedgerService.ClaimKind.DIGEST_CONFLICT) {
                return error(409, "IDEMPOTENCY_KEY_REUSED", claim.actionId(), false, 0);
            }
            if (claim.kind() == ActionLedgerService.ClaimKind.IDEMPOTENT_REPLAY) {
                // Still under lock: winner either registered or finished — if registered, RUNNING.
                return replay(projectId, clientActionId, claim);
            }
            // F3: claim commit + register are atomic w.r.t. same-key followers.
            InFlightRootActions.injectClaimRegisterGapIfConfigured();
            inFlight.register(claim.actionId());
        }

        try {
            return runTurnClaimed(
                    projectId, conversationId, clientActionId, claim.actionId(), text);
        } finally {
            inFlight.clear(claim.actionId());
        }
    }

    private Map<String, Object> runTurnClaimed(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            UUID actionId,
            String text) {
        // Exactly one user utterance for this root turn.
        tx.executeWithoutResult(
                s -> conversations.insertUserMessage(UUID.randomUUID(), conversationId, text));

        UUID taskId =
                tx.execute(
                        status -> {
                            UUID t =
                                    taskRuns.createTask(
                                            projectId, conversationId, TaskStatus.RUNNING);
                            UUID runId =
                                    taskRuns.createRun(t, actionId, RunStatus.RUNNING);
                            actions.bindTaskRun(actionId, conversationId, t, runId);
                            taskRuns.appendEvent(
                                    runId, "RUN_STARTED", null, null, "assistant_turn");
                            return t;
                        });
        UUID runId = actions.find(projectId, clientActionId).orElseThrow().runId();

        if (injectMainAgentSlowMs() > 0) {
            try {
                Thread.sleep(injectMainAgentSlowMs());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        MainAgentTurnContext ctx =
                new MainAgentTurnContext(
                        projectId, conversationId, actionId, runId, "local-user");

        WritingMainAgent.TurnResult agentResult;
        try {
            agentResult = mainAgent.runTurn(ctx, text,
                    line -> persistTrace(runId, List.of(line)));
        } catch (Exception ex) {
            log.warn(
                    "MainAgent failed actionId={} type={} msg={}",
                    actionId,
                    ex.getClass().getName(),
                    ex.getMessage());
            return failAfterPartial(
                    conversationId,
                    actionId,
                    taskId,
                    runId,
                    ex.getClass().getSimpleName() + ": " + safe(ex.getMessage()));
        }

        persistTrace(runId, agentResult.trace());

        if (injectMainAgentFinalFail()) {
            return failAfterPartial(
                    conversationId, actionId, taskId, runId, "INJECTED_MAIN_AGENT_FINAL_FAIL");
        }

        String answer =
                agentResult.answerText() == null || agentResult.answerText().isBlank()
                        ? "{\"kind\":\"ASSISTANT_TURN\",\"note\":\"empty answer\"}"
                        : agentResult.answerText();

        UUID assistantMessageId = UUID.randomUUID();
        Map<String, Object> body =
                successBody(
                        actionId,
                        conversationId,
                        taskId,
                        runId,
                        answer,
                        agentResult.toolNames(),
                        agentResult.trace(),
                        agentResult.modelRequestCount());
        String snapshotJson;
        try {
            snapshotJson = JSON.writeValueAsString(body);
        } catch (Exception e) {
            snapshotJson = "{\"outcome\":\"ASSISTANT_TURN\",\"actionId\":\"" + actionId + "\"}";
        }
        final String snap = snapshotJson;

        tx.executeWithoutResult(
                s -> {
                    taskRuns.requestRunSettlement(
                            runId, RunStatus.SUCCEEDED, agentResult.modelRequestCount());
                    taskRuns.updateTaskStatus(taskId, TaskStatus.WAITING_USER);
                    actions.markResult(
                            actionId,
                            ActionStatus.RESULT_RECORDED,
                            null,
                            true,
                            null,
                            snap,
                            assistantMessageId);
                    conversations.insertAssistantMessage(
                            assistantMessageId, conversationId, answer, runId);
                    taskRuns.publishFinalReply(runId, answer);
                    taskRuns.appendEvent(
                            runId,
                            "ASSISTANT_TURN_DONE",
                            null,
                            null,
                            "tools=" + String.join(",", agentResult.toolNames()));
                });

        return body;
    }

    private void persistTrace(UUID runId, List<WritingMainAgent.TraceLine> trace) {
        if (trace == null) {
            return;
        }
        for (var line : trace) {
            String toolCallId = null;
            if ("TOOL_REQUEST".equals(line.kind()) || "TOOL_RESULT".equals(line.kind())) {
                toolCallId = line.toolCallId();
                if (toolCallId == null || toolCallId.isBlank()) {
                    toolCallId = null;
                }
            }
            final String tc = toolCallId;
            tx.executeWithoutResult(
                    s ->
                            taskRuns.appendTrace(
                                    runId,
                                    "main-trace-" + line.seq(),
                                    line.kind(),
                                    tc,
                                    line.detail() == null ? "" : line.detail()));
        }
    }

    /**
     * After model/stream failure: if child Tools already persisted business results, never claim
     * \"no result\". Prefer UNKNOWN + resultMayExist with honest model counts from events.
     */
    private Map<String, Object> failAfterPartial(
            UUID conversationId, UUID actionId, UUID taskId, UUID runId, String detail) {
        List<ActionLedgerPort.ChapterActionRow> children = actions.listChildToolActions(runId);
        boolean childRecorded =
                children.stream()
                        .anyMatch(
                                c ->
                                        ActionStatus.RESULT_RECORDED.name().equals(c.status())
                                                || ActionStatus.UNKNOWN_GENERATION
                                                        .name()
                                                        .equals(c.status()));
        int modelFromEvents = taskRuns.countEvents(runId, "MODEL_REQUEST");
        int toolResults = taskRuns.countEvents(runId, "TOOL_RESULT");
        boolean childUnresolved = children.stream()
                .anyMatch(c -> ActionStatus.CLAIMED.name().equals(c.status()));
        boolean mayExist = childRecorded || childUnresolved || toolResults > 0;

        ActionStatus ledgerStatus =
                mayExist ? ActionStatus.UNKNOWN_GENERATION : ActionStatus.FAILED;
        RunStatus runStatus = mayExist ? RunStatus.UNKNOWN : RunStatus.FAILED;
        String code = mayExist ? "PARTIAL_TURN_ARTIFACTS_MAY_EXIST" : "MODEL_UNAVAILABLE";
        int http = mayExist ? 200 : 503;

        Map<String, Object> snapBody = new LinkedHashMap<>();
        snapBody.put("actionId", actionId);
        snapBody.put("conversationId", conversationId);
        snapBody.put("taskId", taskId);
        snapBody.put("runId", runId);
        snapBody.put("parentRunId", runId);
        snapBody.put("outcome", mayExist ? "PARTIAL_UNKNOWN" : "FAILED");
        snapBody.put("code", code);
        snapBody.put("actionStatus", ledgerStatus.name());
        snapBody.put("resultMayExist", mayExist);
        snapBody.put("modelRequestCount", modelFromEvents);
        snapBody.put("toolResultCount", toolResults);
        snapBody.put("childActions", children.size());
        snapBody.put(
                "summary",
                mayExist
                        ? "Tool side effects may already be persisted; final answer failed. Do not re-run."
                        : safe(detail));
        String snap;
        try {
            snap = JSON.writeValueAsString(snapBody);
        } catch (Exception e) {
            snap = "{\"outcome\":\"PARTIAL_UNKNOWN\",\"actionId\":\"" + actionId + "\"}";
        }
        final String snapFinal = snap;
        final int modelsFinal = modelFromEvents;

        tx.executeWithoutResult(
                s -> {
                    taskRuns.requestRunSettlement(runId, runStatus, modelsFinal);
                    taskRuns.updateTaskStatus(
                            taskId, mayExist ? TaskStatus.WAITING_USER : TaskStatus.FAILED);
                    actions.markResult(
                            actionId,
                            ledgerStatus,
                            null,
                            mayExist,
                            code,
                            snapFinal,
                            null);
                    conversations.insertAssistantMessage(
                            UUID.randomUUID(),
                            conversationId,
                            "{\"kind\":\"TURN_PARTIAL\",\"code\":\""
                                    + code
                                    + "\",\"actionId\":\""
                                    + actionId
                                    + "\",\"resultMayExist\":"
                                    + mayExist
                                    + ",\"modelRequestCount\":"
                                    + modelsFinal
                                    + ",\"summary\":\""
                                    + safe(detail).replace("\"", "'")
                                    + "\"}", runId);
                    taskRuns.appendEvent(
                            runId,
                            mayExist ? "TURN_PARTIAL_UNKNOWN" : "TURN_FAILED",
                            null,
                            null,
                            code);
                });

        Map<String, Object> err = error(http, code, actionId, mayExist, modelsFinal);
        err.put("outcome", mayExist ? "PARTIAL_UNKNOWN" : "FAILED");
        err.put("actionStatus", ledgerStatus.name());
        err.put("runId", runId);
        err.put("toolResultCount", toolResults);
        err.put("childActions", children.size());
        return err;
    }

    private Map<String, Object> replay(
            UUID projectId, String clientActionId, ActionLedgerService.ClaimResult claim) {
        ActionLedgerPort.ActionLedgerView row =
                actions.find(projectId, clientActionId).orElseThrow();

        // Same-process in-flight: RUNNING only — do not mutate ledger to UNKNOWN.
        if (ActionStatus.CLAIMED.name().equals(row.status())
                && inFlight.isInFlight(claim.actionId())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actionId", claim.actionId());
            body.put("conversationId", row.conversationId());
            body.put("taskId", row.taskId());
            body.put("runId", row.runId());
            body.put("parentRunId", row.runId());
            body.put("actionStatus", ActionStatus.CLAIMED.name());
            body.put("outcome", "RUNNING");
            body.put("httpStatusHint", 202);
            body.put("retryable", false);
            body.put("resultMayExist", false);
            body.put("modelRequestCount", 0);
            return body;
        }

        // Local absence is not proof of a stopped owner. Reads/replays cannot settle execution.
        if (ActionStatus.CLAIMED.name().equals(row.status())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actionId", claim.actionId());
            body.put("conversationId", row.conversationId());
            body.put("taskId", row.taskId());
            body.put("runId", row.runId());
            body.put("parentRunId", row.runId());
            body.put("actionStatus", row.status());
            body.put("outcome", "RUNNING");
            body.put("code", "EXECUTION_OWNER_UNVERIFIED");
            body.put("httpStatusHint", 202);
            body.put("retryable", false);
            body.put("resultMayExist", true);
            body.put("modelRequestCount", 0);
            return body;
        }

        if (ActionStatus.FAILED.name().equals(row.status())
                || ActionStatus.UNKNOWN_GENERATION.name().equals(row.status())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actionId", claim.actionId());
            body.put("conversationId", row.conversationId());
            body.put("taskId", row.taskId());
            body.put("runId", row.runId());
            body.put("parentRunId", row.runId());
            body.put("actionStatus", row.status());
            body.put(
                    "outcome",
                    ActionStatus.FAILED.name().equals(row.status())
                            ? "REPLAY_FAILED"
                            : "REPLAY_UNKNOWN");
            body.put("code", row.errorCode());
            body.put("httpStatusHint", 200);
            body.put("retryable", false);
            body.put("resultMayExist", row.resultMayExist());
            body.put("modelRequestCount", 0);
            if (row.resultSnapshotJson() != null && !row.resultSnapshotJson().isBlank()) {
                body.put("resultSnapshotPresent", true);
            }
            return body;
        }

        if (row.resultSnapshotJson() != null && !row.resultSnapshotJson().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> snap =
                        JSON.readValue(row.resultSnapshotJson(), Map.class);
                Map<String, Object> body = new LinkedHashMap<>(snap);
                body.put("outcome", "REPLAY");
                body.put("actionStatus", row.status());
                body.put("httpStatusHint", 200);
                body.put("retryable", false);
                body.put("resultMayExist", row.resultMayExist());
                body.put("modelRequestCount", 0);
                body.put("resultAssistantMessageId", row.resultAssistantMessageId());
                return body;
            } catch (Exception e) {
                log.warn("snapshot deserialize failed actionId={}", claim.actionId());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionId", claim.actionId());
        body.put("conversationId", row.conversationId());
        body.put("taskId", row.taskId());
        body.put("runId", row.runId());
        body.put("parentRunId", row.runId());
        body.put("actionStatus", row.status());
        body.put("outcome", "REPLAY");
        body.put("httpStatusHint", 200);
        body.put("retryable", false);
        body.put("resultMayExist", row.resultMayExist());
        body.put("modelRequestCount", 0);
        return body;
    }

    private static Map<String, Object> successBody(
            UUID actionId,
            UUID conversationId,
            UUID taskId,
            UUID runId,
            String answer,
            List<String> toolNames,
            List<WritingMainAgent.TraceLine> trace,
            int modelRequestCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionId", actionId);
        body.put("conversationId", conversationId);
        body.put("taskId", taskId);
        body.put("runId", runId);
        body.put("parentRunId", runId);
        body.put("outcome", "ASSISTANT_TURN");
        body.put("answer", answer);
        body.put("toolNames", toolNames);
        body.put("trace", trace);
        body.put("modelRequestCount", modelRequestCount);
        body.put("actionStatus", ActionStatus.RESULT_RECORDED.name());
        body.put("httpStatusHint", 200);
        body.put("retryable", false);
        body.put("resultMayExist", true);
        return body;
    }

    private static Map<String, Object> error(
            int http, String code, UUID actionId, boolean resultMayExist, int modelRequestCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", code);
        body.put("retryable", false);
        body.put("actionId", actionId);
        body.put("resultMayExist", resultMayExist);
        body.put("httpStatusHint", http);
        body.put("modelRequestCount", modelRequestCount);
        return body;
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.substring(0, Math.min(400, s.length()));
    }

    static long injectMainAgentSlowMs() {
        String env = System.getenv("WA2_INJECT_MAIN_AGENT_SLOW_MS");
        if (env == null || env.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(env.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    static boolean injectMainAgentFinalFail() {
        return "1".equals(System.getenv("WA2_INJECT_MAIN_AGENT_FINAL_FAIL"))
                || Boolean.getBoolean("wa2.inject.main.agent.final.fail");
    }
}

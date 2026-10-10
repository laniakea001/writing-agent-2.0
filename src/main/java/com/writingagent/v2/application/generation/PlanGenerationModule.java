package com.writingagent.v2.application.generation;

import com.writingagent.v2.agent.plan.ModelUnavailableException;
import com.writingagent.v2.agent.plan.PlanAgentRuntime;
import com.writingagent.v2.agent.plan.ReadCurrentProjectContextTool;
import com.writingagent.v2.agent.plan.StructuredOutputMissingException;
import com.writingagent.v2.application.ActionLedgerService;
import com.writingagent.v2.application.PlanPersistenceUseCase;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.TaskRunService;
import com.writingagent.v2.application.WorkbenchTurnService;
import com.writingagent.v2.application.port.ActionLedgerPort;
import com.writingagent.v2.application.port.ConversationPort;
import com.writingagent.v2.domain.ActionStatus;
import com.writingagent.v2.domain.PlanOutcome;
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

/**
 * Shared plan-generation use case. Does not write conversation chat — callers own product messages.
 */
@Service
public class PlanGenerationModule {

    private static final Logger log = LoggerFactory.getLogger(PlanGenerationModule.class);

    private final ProjectApplicationService projects;
    private final ActionLedgerService actions;
    private final TaskRunService taskRuns;
    private final PlanPersistenceUseCase persistence;
    private final PlanAgentRuntime planAgent;
    private final TransactionTemplate tx;
    private final ConversationPort conversations;

    public PlanGenerationModule(
            ProjectApplicationService projects,
            ActionLedgerService actions,
            TaskRunService taskRuns,
            PlanPersistenceUseCase persistence,
            PlanAgentRuntime planAgent,
            TransactionTemplate tx,
            ConversationPort conversations) {
        this.projects = projects;
        this.actions = actions;
        this.taskRuns = taskRuns;
        this.persistence = persistence;
        this.planAgent = planAgent;
        this.tx = tx;
        this.conversations = conversations;
    }

    public Map<String, Object> execute(GenerationCommand cmd) {
        UUID projectId = cmd.projectId();
        UUID conversationId = cmd.conversationId();
        String clientActionId = cmd.clientActionId();
        String message = cmd.userMessage();

        String digest =
                ActionLedgerService.digestFor(
                        projectId, conversationId, null, WorkbenchTurnService.OP, message, null);
        var claim = actions.claim(projectId, clientActionId, digest, WorkbenchTurnService.OP);
        if (claim.kind() == ActionLedgerService.ClaimKind.DIGEST_CONFLICT) {
            return error(409, "IDEMPOTENCY_KEY_REUSED", claim.actionId(), false, 0);
        }
        if (claim.kind() == ActionLedgerService.ClaimKind.IDEMPOTENT_REPLAY) {
            return replayProjection(projectId, clientActionId, claim);
        }

        if (cmd.messages().recordUserMessageAfterClaim()) {
            tx.executeWithoutResult(
                    s ->
                            conversations.insertUserMessage(
                                    UUID.randomUUID(),
                                    conversationId,
                                    message == null ? "" : message));
        }

        UUID taskId =
                tx.execute(
                        status -> {
                            UUID t = taskRuns.createTask(projectId, conversationId, TaskStatus.RUNNING);
                            UUID runId =
                                    taskRuns.createRun(
                                            t, claim.actionId(), RunStatus.RUNNING, cmd.parentRunId(), cmd.parentToolCallId());
                            actions.bindTaskRun(claim.actionId(), conversationId, t, runId);
                            taskRuns.appendEvent(runId, "RUN_STARTED", null, null, "run_started");
                            return t;
                        });

        ActionLedgerPort.ActionLedgerView actionRow =
                actions.find(projectId, clientActionId).orElseThrow();
        UUID runId = actionRow.runId();

        String goal = projects.currentResearchGoal(projectId);
        PlanAgentRuntime.AgentRunResult agentResult;
        try {
            agentResult = planAgent.run(projectId, claim.actionId(), goal, message,
                    line -> persistTrace(runId, claim.actionId(), List.of(line)), runId);
        } catch (StructuredOutputMissingException ex) {
            return failTurn(
                    claim.actionId(),
                    taskId,
                    runId,
                    WorkbenchTurnService.CODE_STRUCTURED_OUTPUT_MISSING,
                    502,
                    ex.modelRequestCount(),
                    ex.trace(),
                    ex.getMessage());
        } catch (ModelUnavailableException ex) {
            return failTurn(
                    claim.actionId(),
                    taskId,
                    runId,
                    WorkbenchTurnService.CODE_MODEL_UNAVAILABLE,
                    503,
                    ex.modelRequestCount(),
                    ex.trace(),
                    ex.getMessage());
        } catch (Exception ex) {
            log.warn(
                    "PlanAgent turn failed actionId={} type={} msg={}",
                    claim.actionId(),
                    ex.getClass().getName(),
                    ex.getMessage());
            return failTurn(
                    claim.actionId(),
                    taskId,
                    runId,
                    WorkbenchTurnService.CODE_MODEL_UNAVAILABLE,
                    503,
                    0,
                    List.of(),
                    ex.getClass().getSimpleName() + ": " + safeMsg(ex.getMessage()));
        }

        if (agentResult.modelRequestCount() > 8) {
            taskRuns.appendEvent(
                    runId,
                    "MODEL_BUDGET_WARNING",
                    null,
                    null,
                    "modelRequests=" + agentResult.modelRequestCount());
        }

        persistTrace(runId, claim.actionId(), agentResult.trace());

        PlanOutcome outcome = agentResult.outcome();
        if (outcome == null) {
            tx.executeWithoutResult(
                    s -> {
                        taskRuns.requestRunSettlement(
                                runId, RunStatus.FAILED, agentResult.modelRequestCount());
                        taskRuns.updateTaskStatus(taskId, TaskStatus.FAILED);
                        actions.markResult(
                                claim.actionId(),
                                ActionStatus.FAILED,
                                null,
                                false,
                                WorkbenchTurnService.CODE_MODEL_OUTPUT_INVALID);
                    });
            Map<String, Object> err =
                    error(
                            502,
                            WorkbenchTurnService.CODE_MODEL_OUTPUT_INVALID,
                            claim.actionId(),
                            false,
                            agentResult.modelRequestCount());
            err.put("failureDetail", "null PlanOutcome");
            return err;
        }

        PlanPersistenceUseCase.PersistResult persisted;
        try {
            persisted =
                    tx.execute(
                            s ->
                                    persistence.persistAfterAgent(
                                            projectId,
                                            conversationId,
                                            claim.actionId(),
                                            taskId,
                                            runId,
                                            outcome,
                                            agentResult.modelRequestCount(),
                                            cmd.messages().sink()));
        } catch (PlanPersistenceUseCase.InvalidPlanOutcomeException ex) {
            Map<String, Object> failed =
                    failTurn(
                            claim.actionId(),
                            taskId,
                            runId,
                            WorkbenchTurnService.CODE_MODEL_OUTPUT_INVALID,
                            422,
                            agentResult.modelRequestCount(),
                            List.of(),
                            "schema=" + ex.code());
            failed.put("failureDetail", "schema=" + ex.code());
            return failed;
        } catch (RuntimeException ex) {
            Map<String, Object> failed =
                    failTurn(
                            claim.actionId(),
                            taskId,
                            runId,
                            "PERSIST_UNKNOWN",
                            500,
                            agentResult.modelRequestCount(),
                            List.of(),
                            ex.getClass().getSimpleName() + ": " + safeMsg(ex.getMessage()));
            failed.put(
                    "failureDetail",
                    ex.getClass().getSimpleName() + ": " + safeMsg(ex.getMessage()));
            return failed;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionId", persisted.actionId());
        body.put("conversationId", conversationId);
        body.put("taskId", persisted.taskId());
        body.put("runId", persisted.runId());
        body.put("actionStatus", ActionStatus.RESULT_RECORDED.name());
        body.put("taskStatus", persisted.taskStatus().name());
        body.put("runStatus", RunStatus.SUCCEEDED.name());
        body.put("outcome", persisted.apiOutcome());
        body.put("planRevisionId", persisted.planRevisionId());
        body.put("planRevisionNo", persisted.revisionNo());
        body.put("modelRequestCount", agentResult.modelRequestCount());
        body.put("httpStatusHint", 200);
        body.put("retryable", false);
        body.put("resultMayExist", persisted.planRevisionId() != null);
        body.put("invocationOrigin", cmd.messages().origin().name());
        return body;
    }

    private Map<String, Object> failTurn(
            UUID actionId,
            UUID taskId,
            UUID runId,
            String code,
            int http,
            int modelRequestCount,
            List<PlanAgentRuntime.TraceLine> trace,
            String detail) {
        log.warn(
                "PlanAgent turn failed actionId={} code={} modelRequests={} detail={}",
                actionId,
                code,
                modelRequestCount,
                detail);
        List<PlanAgentRuntime.TraceLine> safeTrace = trace == null ? List.of() : trace;
        boolean mayExist =
                "PERSIST_UNKNOWN".equals(code)
                        || "UNKNOWN_GENERATION".equals(code)
                        || (code != null && code.contains("UNKNOWN"));
        ActionStatus ledgerStatus =
                mayExist ? ActionStatus.UNKNOWN_GENERATION : ActionStatus.FAILED;
        RunStatus runStatus = mayExist ? RunStatus.UNKNOWN : RunStatus.FAILED;
        tx.executeWithoutResult(
                s -> {
                    persistTrace(runId, actionId, safeTrace);
                    taskRuns.requestRunSettlement(runId, runStatus, modelRequestCount);
                    taskRuns.updateTaskStatus(taskId, TaskStatus.FAILED);
                    actions.markResult(actionId, ledgerStatus, null, mayExist, code);
                    String summary =
                            code
                                    + ": "
                                    + (detail == null
                                            ? ""
                                            : detail.substring(0, Math.min(400, detail.length())));
                    taskRuns.appendEvent(runId, "RUN_FAILED", null, null, summary);
                });
        Map<String, Object> err = error(http, code, actionId, mayExist, modelRequestCount);
        err.put("failureDetail", detail == null ? "" : detail);
        return err;
    }

    private void persistTrace(UUID runId, UUID actionId, List<PlanAgentRuntime.TraceLine> trace) {
        if (trace == null) {
            return;
        }
        for (var line : trace) {
            String toolCallId =
                    line.kind().startsWith("TOOL") ? line.kind() + ":" + line.seq() : null;
            if ("TOOL_REQUEST".equals(line.kind())
                    && line.detail() != null
                    && line.detail().contains(ReadCurrentProjectContextTool.TOOL_NAME)) {
                toolCallId = "biz-" + actionId + "-read";
            }
            if ("TOOL_PHASE_END".equals(line.kind())
                    && line.detail() != null
                    && line.detail().contains(ReadCurrentProjectContextTool.TOOL_NAME)) {
                toolCallId = "biz-" + actionId + "-read-end";
            }
            if ("STRUCTURED_OUTPUT_MISSING".equals(line.kind())) {
                toolCallId = "so-missing-" + actionId + "-" + line.seq();
            }
            final String tc = toolCallId;
            tx.executeWithoutResult(
                    s ->
                            taskRuns.appendTrace(
                                    runId, "stage-trace-" + line.seq(), line.kind(), tc,
                                    redactDetail(line.detail())));
        }
    }

    private Map<String, Object> replayProjection(
            UUID projectId, String clientActionId, ActionLedgerService.ClaimResult claim) {
        ActionLedgerPort.ActionLedgerView row =
                actions.find(projectId, clientActionId).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionId", claim.actionId());
        body.put("conversationId", row.conversationId());
        body.put("taskId", row.taskId());
        body.put("runId", row.runId());
        body.put("actionStatus", row.status());
        body.put("planRevisionId", row.planRevisionId());
        body.put("outcome", "REPLAY");
        body.put(
                "httpStatusHint",
                ActionStatus.CLAIMED.name().equals(row.status()) ? 202 : 200);
        body.put("retryable", false);
        body.put("resultMayExist", row.resultMayExist());
        body.put("modelRequestCount", 0);
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

    private static String redactDetail(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.replaceAll("(?i)(api[_-]?key|authorization|bearer)\\s*[:=]\\s*\\S+", "$1=***");
    }

    private static String safeMsg(String s) {
        if (s == null) {
            return "";
        }
        return s.substring(0, Math.min(400, s.length()));
    }
}

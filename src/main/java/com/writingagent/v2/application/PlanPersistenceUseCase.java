package com.writingagent.v2.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.generation.PresentationSink;
import com.writingagent.v2.application.port.ConversationPort;
import com.writingagent.v2.application.port.PlanRevisionPort;
import com.writingagent.v2.application.port.ProjectPort;
import com.writingagent.v2.application.support.RequestDigests;
import com.writingagent.v2.domain.ActionStatus;
import com.writingagent.v2.domain.PlanOutcome;
import com.writingagent.v2.domain.PlanOutcomeValidator;
import com.writingagent.v2.domain.RunStatus;
import com.writingagent.v2.domain.TaskStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists validated PlanOutcome in a single transaction. Agent must not write via tools.
 * Invalid outcomes throw before any FAILED mark — caller settles ledger in a separate tx (M9).
 */
@Service
public class PlanPersistenceUseCase {

    public static final class InvalidPlanOutcomeException extends IllegalArgumentException {
        private final String code;

        public InvalidPlanOutcomeException(String code) {
            super(code);
            this.code = code == null ? "MODEL_OUTPUT_INVALID" : code;
        }

        public String code() {
            return code;
        }
    }

    public record PersistResult(
            UUID actionId,
            UUID taskId,
            UUID runId,
            UUID planRevisionId,
            Long revisionNo,
            TaskStatus taskStatus,
            String apiOutcome) {}

    private final PlanRevisionPort plans;
    private final ProjectPort projects;
    private final ConversationPort conversations;
    private final ActionLedgerService actionLedger;
    private final TaskRunService taskRunService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public PlanPersistenceUseCase(
            PlanRevisionPort plans,
            ProjectPort projects,
            ConversationPort conversations,
            ActionLedgerService actionLedger,
            TaskRunService taskRunService) {
        this.plans = plans;
        this.projects = projects;
        this.conversations = conversations;
        this.actionLedger = actionLedger;
        this.taskRunService = taskRunService;
    }

    @Transactional
    public PersistResult persistAfterAgent(
            UUID projectId,
            UUID conversationId,
            UUID actionId,
            UUID taskId,
            UUID runId,
            PlanOutcome outcome,
            int modelRequestCount) {
        return persistAfterAgent(
                projectId,
                conversationId,
                actionId,
                taskId,
                runId,
                outcome,
                modelRequestCount,
                PresentationSink.CHAT_AND_ARTIFACT);
    }

    @Transactional
    public PersistResult persistAfterAgent(
            UUID projectId,
            UUID conversationId,
            UUID actionId,
            UUID taskId,
            UUID runId,
            PlanOutcome outcome,
            int modelRequestCount,
            PresentationSink sink) {
        if (outcome == null || outcome.outcome == null) {
            throw new InvalidPlanOutcomeException("OUTCOME_REQUIRED");
        }
        if (outcome.evidenceBasis == null || outcome.evidenceBasis.isBlank()) {
            outcome.evidenceBasis = PlanOutcome.SOURCE_BASIS;
        }

        String schemaErr = PlanOutcomeValidator.validateOutcome(outcome);
        if (schemaErr != null) {
            // Do NOT mark FAILED here — that would roll back with this throw and leave CLAIMED.
            throw new InvalidPlanOutcomeException(schemaErr);
        }

        taskRunService.guardBusinessCommit(runId);

        if (PlanOutcome.CLARIFICATION_REQUIRED.equals(outcome.outcome)) {
            if (sink == PresentationSink.CHAT_AND_ARTIFACT) {
                try {
                    String json = objectMapper.writeValueAsString(outcome);
                    conversations.insertAssistantMessage(UUID.randomUUID(), conversationId, json, runId);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            taskRunService.requestRunSettlement(runId, RunStatus.SUCCEEDED, modelRequestCount);
            taskRunService.updateTaskStatus(taskId, TaskStatus.NEEDS_INPUT);
            taskRunService.appendEvent(runId, "WAITING_USER", null, null, "clarification_required");
            actionLedger.markResult(actionId, ActionStatus.RESULT_RECORDED, null, false, null);
            return new PersistResult(
                    actionId, taskId, runId, null, null, TaskStatus.NEEDS_INPUT, "NEEDS_INPUT");
        }

        long nextRev = plans.nextRevisionNo(projectId);
        UUID planRevisionId = UUID.randomUUID();
        String payloadJson;
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("outcome", outcome.outcome);
            payload.put("plan", outcome.plan);
            payload.put("evidenceBasis", outcome.evidenceBasis);
            payload.put("limitations", outcome.limitations);
            payload.put("contextMarkEcho", outcome.contextMarkEcho);
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String hash = RequestDigests.contentHash(payloadJson);
        plans.insertDraft(
                planRevisionId,
                projectId,
                nextRev,
                payloadJson,
                hash,
                PlanOutcome.SOURCE_BASIS);
        projects.setCurrentPlanRevision(projectId, planRevisionId);
        taskRunService.requestRunSettlement(runId, RunStatus.SUCCEEDED, modelRequestCount);
        taskRunService.updateTaskStatus(taskId, TaskStatus.WAITING_USER);
        taskRunService.appendEvent(
                runId, "PLAN_DRAFT_SAVED", null, planRevisionId.toString(), "draft_saved");
        taskRunService.appendEvent(runId, "WAITING_USER", null, null, "awaiting_confirm");
        actionLedger.markResult(
                actionId, ActionStatus.RESULT_RECORDED, planRevisionId, true, null);
        return new PersistResult(
                actionId,
                taskId,
                runId,
                planRevisionId,
                nextRev,
                TaskStatus.WAITING_USER,
                "PLAN_DRAFT_READY");
    }
}

package com.writingagent.v2.support;

import com.writingagent.v2.application.ActionLedgerService;
import com.writingagent.v2.application.PlanPersistenceUseCase;
import com.writingagent.v2.application.TaskRunService;
import com.writingagent.v2.domain.PlanOutcome;
import com.writingagent.v2.domain.RunStatus;
import com.writingagent.v2.domain.TaskStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only deterministic PlanOutcome driver for Application/Repository persistence contract.
 * Registered only via {@link FakePlanTurnTestConfig}; must not be component-scanned into production.
 */
public class FakePlanTurnOrchestrator {

    public static final String OP = "GENERATE_OR_CONTINUE_PLAN";

    private final ActionLedgerService actions;
    private final TaskRunService taskRuns;
    private final PlanPersistenceUseCase persistence;

    public FakePlanTurnOrchestrator(
            ActionLedgerService actions,
            TaskRunService taskRuns,
            PlanPersistenceUseCase persistence) {
        this.actions = actions;
        this.taskRuns = taskRuns;
        this.persistence = persistence;
    }

    public record TurnResult(
            ActionLedgerService.ClaimKind claimKind,
            UUID actionId,
            UUID taskId,
            UUID runId,
            UUID planRevisionId,
            Long revisionNo,
            String apiOutcome,
            String errorCode) {}

    @Transactional
    public TurnResult turn(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            String message,
            PlanOutcome outcome,
            int modelRequestCount) {
        String digest =
                ActionLedgerService.digestFor(
                        projectId, conversationId, null, OP, message, null);
        var claim = actions.claim(projectId, clientActionId, digest, OP);
        if (claim.kind() == ActionLedgerService.ClaimKind.DIGEST_CONFLICT) {
            return new TurnResult(
                    claim.kind(),
                    claim.actionId(),
                    null,
                    claim.runId(),
                    null,
                    null,
                    null,
                    "IDEMPOTENCY_KEY_REUSED");
        }
        if (claim.kind() == ActionLedgerService.ClaimKind.IDEMPOTENT_REPLAY) {
            var row = actions.find(projectId, clientActionId).orElseThrow();
            return new TurnResult(
                    claim.kind(),
                    claim.actionId(),
                    row.taskId(),
                    row.runId(),
                    row.planRevisionId(),
                    null,
                    null,
                    null);
        }

        UUID taskId = taskRuns.createTask(projectId, conversationId, TaskStatus.RUNNING);
        UUID runId = taskRuns.createRun(taskId, claim.actionId(), RunStatus.RUNNING);
        actions.bindTaskRun(claim.actionId(), conversationId, taskId, runId);
        taskRuns.appendEvent(runId, "RUN_STARTED", null, null, "run_started");

        var persisted =
                persistence.persistAfterAgent(
                        projectId,
                        conversationId,
                        claim.actionId(),
                        taskId,
                        runId,
                        outcome,
                        modelRequestCount);
        return new TurnResult(
                claim.kind(),
                persisted.actionId(),
                persisted.taskId(),
                persisted.runId(),
                persisted.planRevisionId(),
                persisted.revisionNo(),
                persisted.apiOutcome(),
                null);
    }

    public static PlanOutcome sampleDraft() {
        PlanOutcome o = new PlanOutcome();
        o.outcome = PlanOutcome.PLAN_DRAFT_READY;
        o.evidenceBasis = PlanOutcome.SOURCE_BASIS;
        o.contextMarkEcho = "CP1-FAKE-MARK";
        o.limitations = List.of("No project retrieval in this phase");
        PlanOutcome.PlanBody plan = new PlanOutcome.PlanBody();
        plan.researchQuestion = "平台与监管责任边界如何界定？";
        plan.goals = "形成第二章研究计划草案";
        plan.scope = "制度文本比较，不含个案访谈";
        plan.chapterOutline = List.of("2.1 概念", "2.2 责任配置", "2.3 反方观点");
        plan.methodSuggestion = "制度文本比较";
        plan.expectedDeliverables = "研究计划 DRAFT";
        plan.risksOrAssumptions = List.of("未使用项目资料");
        o.plan = plan;
        return o;
    }

    public static PlanOutcome sampleClarification() {
        PlanOutcome o = new PlanOutcome();
        o.outcome = PlanOutcome.CLARIFICATION_REQUIRED;
        o.evidenceBasis = PlanOutcome.SOURCE_BASIS;
        o.clarificationQuestions = List.of("研究对象是什么？", "体裁是论文章节还是课题提纲？");
        return o;
    }
}

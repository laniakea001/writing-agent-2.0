package com.writingagent.v2.application;

import com.writingagent.v2.application.port.PlanRevisionPort;
import com.writingagent.v2.application.port.ProjectPort;
import com.writingagent.v2.application.port.TaskRunPort;
import com.writingagent.v2.application.support.RequestDigests;
import com.writingagent.v2.domain.PlanOutcomeValidator;
import com.writingagent.v2.domain.PlanRevisionStatus;
import com.writingagent.v2.domain.TaskStatus;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanEditConfirmUseCase {

    public static class StalePlanRevisionException extends RuntimeException {
        public StalePlanRevisionException(String msg) {
            super(msg);
        }
    }

    public static class InvalidPlanPatchException extends IllegalArgumentException {
        private final String code;

        public InvalidPlanPatchException(String code) {
            super(code);
            this.code = code == null ? "PLAN_PATCH_INVALID" : code;
        }

        public String code() {
            return code;
        }
    }

    private final PlanRevisionPort plans;
    private final ProjectPort projects;
    private final TaskRunPort tasks;

    public PlanEditConfirmUseCase(
            PlanRevisionPort plans, ProjectPort projects, TaskRunPort tasks) {
        this.plans = plans;
        this.projects = projects;
        this.tasks = tasks;
    }

    @Transactional
    public Map<String, Object> edit(
            UUID projectId, UUID planRevisionId, long expectedRevisionNo, String patchJson) {
        String schemaErr = PlanOutcomeValidator.validatePatchJson(patchJson);
        if (schemaErr != null) {
            throw new InvalidPlanPatchException(schemaErr);
        }
        ProjectPort.ProjectView project = loadProject(projectId);
        UUID current = project.currentPlanRevisionId();
        if (current == null || !current.equals(planRevisionId)) {
            throw new StalePlanRevisionException("current pointer mismatch");
        }
        PlanRevisionPort.PlanRevisionView rev = plans.findById(planRevisionId);
        if (rev.revisionNo() != expectedRevisionNo
                || !PlanRevisionStatus.DRAFT.name().equals(rev.status())) {
            throw new StalePlanRevisionException("revision cas failed");
        }
        long newNo = rev.revisionNo() + 1;
        UUID newId = UUID.randomUUID();
        String hash = RequestDigests.contentHash(patchJson);
        plans.insertDraft(
                newId,
                projectId,
                newNo,
                patchJson,
                hash,
                "USER_GOAL_AND_MODEL_SYNTHESIS");
        plans.markSuperseded(planRevisionId);
        projects.setCurrentPlanRevision(projectId, newId);
        return Map.of(
                "oldPlanRevisionId",
                planRevisionId,
                "newPlanRevisionId",
                newId,
                "newRevisionNo",
                newNo);
    }

    @Transactional
    public Map<String, Object> confirm(
            UUID projectId,
            UUID planRevisionId,
            long expectedRevisionNo,
            String confirmedBy,
            UUID taskId) {
        ProjectPort.ProjectView project = loadProject(projectId);
        UUID current = project.currentPlanRevisionId();
        if (current == null || !current.equals(planRevisionId)) {
            throw new StalePlanRevisionException("STALE_PLAN_REVISION");
        }
        PlanRevisionPort.PlanRevisionView before = plans.findById(planRevisionId);
        String schemaErr = PlanOutcomeValidator.validatePatchJson(before.payloadJson());
        if (schemaErr != null) {
            throw new InvalidPlanPatchException(schemaErr);
        }
        if (before.revisionNo() != expectedRevisionNo
                || !PlanRevisionStatus.DRAFT.name().equals(before.status())) {
            throw new StalePlanRevisionException("STALE_PLAN_REVISION");
        }
        int updated =
                plans.confirmDraftIfMatches(planRevisionId, expectedRevisionNo, confirmedBy);
        if (updated != 1) {
            throw new StalePlanRevisionException("STALE_PLAN_REVISION");
        }
        UUID stillCurrent = projects.currentPlanRevisionId(projectId);
        if (stillCurrent == null || !stillCurrent.equals(planRevisionId)) {
            throw new StalePlanRevisionException("STALE_PLAN_REVISION");
        }
        if (taskId != null) {
            tasks.updateTaskStatus(taskId, TaskStatus.COMPLETED);
        }
        PlanRevisionPort.PlanRevisionView rev = plans.findById(planRevisionId);
        return Map.of(
                "planRevisionId",
                planRevisionId,
                "confirmedRevisionNo",
                rev.revisionNo(),
                "contentHash",
                rev.contentHash(),
                "status",
                PlanRevisionStatus.CONFIRMED.name());
    }

    private ProjectPort.ProjectView loadProject(UUID projectId) {
        try {
            return projects.getProject(projectId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("PROJECT_NOT_FOUND");
        }
    }
}

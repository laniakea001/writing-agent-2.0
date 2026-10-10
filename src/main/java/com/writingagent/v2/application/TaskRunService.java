package com.writingagent.v2.application;

import com.writingagent.v2.application.port.TaskRunPort;
import com.writingagent.v2.domain.RunStatus;
import com.writingagent.v2.domain.TaskStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskRunService {

    private final TaskRunPort tasks;
    private final com.writingagent.v2.runtime.RunJournal journal;
    private final com.writingagent.v2.runtime.RunSupervisor supervisor;

    public TaskRunService(TaskRunPort tasks) {
        this.tasks = tasks;
        this.journal = null;
        this.supervisor = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskRunService(TaskRunPort tasks, com.writingagent.v2.runtime.RunJournal journal,
            com.writingagent.v2.runtime.RunSupervisor supervisor) {
        this.tasks = tasks;
        this.journal = journal;
        this.supervisor = supervisor;
    }

    @Transactional
    public UUID createTask(UUID projectId, UUID conversationId, TaskStatus status) {
        UUID taskId = UUID.randomUUID();
        tasks.insertTask(taskId, projectId, conversationId, status);
        return taskId;
    }

    public void updateTaskStatus(UUID taskId, TaskStatus status) {
        tasks.updateTaskStatus(taskId, status);
    }

    @Transactional
    public UUID createRun(UUID taskId, UUID actionId, RunStatus status) {
        return createRun(taskId, actionId, status, null);
    }

    @Transactional
    public UUID createRun(UUID taskId, UUID actionId, RunStatus status, UUID parentRunId) {
        return createRun(taskId, actionId, status, parentRunId, null);
    }

    @Transactional
    public UUID createRun(UUID taskId, UUID actionId, RunStatus status, UUID parentRunId, String parentToolCallId) {
        if (journal != null) return journal.create(taskId, actionId, parentRunId, parentToolCallId).runId();
        UUID runId = UUID.randomUUID();
        tasks.insertRun(runId, taskId, actionId, status, parentRunId);
        return runId;
    }

    public void updateRunStatus(UUID runId, RunStatus status, int modelRequestCount) {
        tasks.updateRunStatus(runId, status, modelRequestCount);
    }

    public void requestRunSettlement(UUID runId, RunStatus status, int models) {
        supervisor.requestSettlement(runId, status, models);
    }

    public void guardBusinessCommit(UUID runId) { journal.guardBusinessCommit(runId); }

    public void publishFinalReply(UUID runId, String text) {
        journal.append(runId, new com.writingagent.v2.runtime.RunJournal.Draft(
                "business:final-reply", "ASSISTANT_MESSAGE_SAVED", "CONTENT", null, null, true,
                java.util.Map.of("text", text, "replyId", "final")));
    }

    @Transactional
    public long publishBusinessEvent(UUID runId, String type, String artifactId, String summary) {
        String output = switch (type) {
            case "PLAN_DRAFT_SAVED", "CHAPTER_SAVED", "CHAPTER_REWRITTEN" -> "ARTIFACT_SAVED";
            default -> "STATUS";
        };
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("kind", type);
        payload.put("label", summary == null ? "" : summary);
        if (artifactId != null) payload.put("artifactId", artifactId);
        return journal.append(runId, new com.writingagent.v2.runtime.RunJournal.Draft(
                "business:" + type + ":" + (artifactId == null ? UUID.randomUUID() : artifactId),
                type, output, null, null, true, payload));
    }

    /** Append event; duplicate tool_call_id within run is ignored (observer double-subscribe). */
    public long appendEvent(
            UUID runId, String eventType, String toolCallId, String correlationId, String summary) {
        if (journal != null && journal.get(runId).protocolVersion() == 2)
            return publishBusinessEvent(runId, eventType, correlationId, summary);
        return tasks.appendEvent(runId, eventType, toolCallId, correlationId, summary);
    }

    public int countEvents(UUID runId, String eventType) {
        if (journal != null && journal.get(runId).protocolVersion() == 2) {
            eventType = switch (eventType) {
                case "MODEL_REQUEST" -> "MODEL_STARTED";
                case "TOOL_RESULT" -> "TOOL_FINISHED";
                default -> eventType;
            };
        }
        return tasks.countEvents(runId, eventType);
    }

    /** End-of-run snapshot replay must not duplicate the already published live trace. */
    @Transactional
    public long appendTrace(UUID runId, String traceKey, String eventType,
            String toolCallId, String summary) {
        if (journal != null && journal.get(runId).protocolVersion() == 2) {
            // Legacy diagnostic callbacks are not native tool events. Do not forge a card ID.
            return journal.append(runId, new com.writingagent.v2.runtime.RunJournal.Draft(
                    "diagnostic:" + traceKey, eventType, "INTERNAL", null, null, false,
                    java.util.Map.of("kind", eventType)));
        }
        Long existing = tasks.findTraceSequence(runId, traceKey);
        return existing != null ? existing
                : tasks.appendEvent(runId, eventType, toolCallId, traceKey, summary);
    }
}

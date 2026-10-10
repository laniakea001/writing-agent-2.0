package com.writingagent.v2.application.port;

import com.writingagent.v2.domain.RunStatus;
import com.writingagent.v2.domain.TaskStatus;
import java.util.UUID;

/** Persistence port for writing_task / agent_run / run_event. */
public interface TaskRunPort {

    void insertTask(UUID taskId, UUID projectId, UUID conversationId, TaskStatus status);

    void updateTaskStatus(UUID taskId, TaskStatus status);

    void insertRun(UUID runId, UUID taskId, UUID actionId, RunStatus status);

    /** Child run under a parent Main-Agent / fan-out run; {@code parentRunId} may be null. */
    void insertRun(UUID runId, UUID taskId, UUID actionId, RunStatus status, UUID parentRunId);

    void updateRunStatus(UUID runId, RunStatus status, int modelRequestCount);

    /**
     * Append run_event. Duplicate non-null tool_call_id within a run is ignored (observer
     * re-subscribe). Returns the seq used (new or existing).
     */
    long appendEvent(
            UUID runId, String eventType, String toolCallId, String correlationId, String summary);

    /** Count run_event rows of a given type for model/tool accounting after partial failure. */
    int countEvents(UUID runId, String eventType);

    record RunEventView(UUID runId, UUID parentRunId, String operation, long sequence, String eventType,
            String toolCallId, String summary, java.time.Instant createdAt) {}

    /** Committed events only; includes descendants of the exact root Run. */
    java.util.List<RunEventView> listExecutionEvents(UUID rootRunId);

    Long findTraceSequence(UUID runId, String correlationId);

    record ExecutionView(String clientActionId, UUID runId, UUID conversationId, String operation,
            String status, String errorCode, java.time.Instant createdAt) {}
    java.util.List<ExecutionView> listExecutions(UUID projectId, UUID conversationId);
}

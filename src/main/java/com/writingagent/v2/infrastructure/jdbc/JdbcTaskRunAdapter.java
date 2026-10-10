package com.writingagent.v2.infrastructure.jdbc;

import com.writingagent.v2.application.port.TaskRunPort;
import com.writingagent.v2.domain.RunStatus;
import com.writingagent.v2.domain.TaskStatus;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTaskRunAdapter implements TaskRunPort {

    private final JdbcTemplate jdbc;

    public JdbcTaskRunAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertTask(UUID taskId, UUID projectId, UUID conversationId, TaskStatus status) {
        jdbc.update(
                """
                INSERT INTO writing_v2.writing_task(task_id, project_id, conversation_id, status, workspace_id)
                VALUES (?, ?, ?, ?,
                  (SELECT workspace_id FROM writing_v2.writing_project WHERE project_id = ?))
                """,
                taskId,
                projectId,
                conversationId,
                status.name(),
                projectId);
    }

    @Override
    public void updateTaskStatus(UUID taskId, TaskStatus status) {
        jdbc.update(
                """
                UPDATE writing_v2.writing_task SET status = ?, updated_at = NOW() WHERE task_id = ?
                """,
                status.name(),
                taskId);
    }

    @Override
    public void insertRun(UUID runId, UUID taskId, UUID actionId, RunStatus status) {
        insertRun(runId, taskId, actionId, status, null);
    }

    @Override
    public void insertRun(
            UUID runId, UUID taskId, UUID actionId, RunStatus status, UUID parentRunId) {
        jdbc.update(
                """
                INSERT INTO writing_v2.agent_run(
                  run_id, task_id, action_id, status, event_seq_counter, parent_run_id)
                VALUES (?, ?, ?, ?, 0, ?)
                """,
                runId,
                taskId,
                actionId,
                status.name(),
                parentRunId);
    }

    @Override
    public void updateRunStatus(UUID runId, RunStatus status, int modelRequestCount) {
        rejectV2Write(runId);
        jdbc.update(
                """
                UPDATE writing_v2.agent_run
                SET status = ?, model_request_count = ?, updated_at = NOW()
                WHERE run_id = ?
                """,
                status.name(),
                modelRequestCount,
                runId);
    }

    @Override
    public long appendEvent(
            UUID runId, String eventType, String toolCallId, String correlationId, String summary) {
        rejectV2Write(runId);
        Long seq =
                jdbc.queryForObject(
                        """
                        UPDATE writing_v2.agent_run
                        SET event_seq_counter = event_seq_counter + 1, updated_at = NOW()
                        WHERE run_id = ?
                        RETURNING event_seq_counter
                        """,
                        Long.class,
                        runId);
        if (seq == null) {
            throw new IllegalStateException("run not found for event append: " + runId);
        }
        UUID eventId = UUID.randomUUID();
        boolean phaseUnique =
                toolCallId != null
                        && ("TOOL_REQUEST".equals(eventType) || "TOOL_RESULT".equals(eventType));
        int n;
        if (phaseUnique) {
            n =
                    jdbc.update(
                            """
                            INSERT INTO writing_v2.run_event(
                              event_id, run_id, seq, event_type, tool_call_id, correlation_id, summary)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (run_id, tool_call_id, event_type)
                              WHERE tool_call_id IS NOT NULL
                                AND event_type IN ('TOOL_REQUEST', 'TOOL_RESULT')
                            DO NOTHING
                            """,
                            eventId,
                            runId,
                            seq,
                            eventType,
                            toolCallId,
                            correlationId,
                            summary);
            if (n == 0) {
                Long existing =
                        jdbc.queryForObject(
                                """
                                SELECT seq FROM writing_v2.run_event
                                WHERE run_id = ? AND tool_call_id = ? AND event_type = ?
                                """,
                                Long.class,
                                runId,
                                toolCallId,
                                eventType);
                if (existing == null) {
                    throw new IllegalStateException(
                            "event insert conflict without matching TOOL phase row run="
                                    + runId
                                    + " toolCallId="
                                    + toolCallId
                                    + " type="
                                    + eventType);
                }
                return existing;
            }
            return seq;
        }
        jdbc.update(
                """
                INSERT INTO writing_v2.run_event(
                  event_id, run_id, seq, event_type, tool_call_id, correlation_id, summary)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                runId,
                seq,
                eventType,
                toolCallId,
                correlationId,
                summary);
        return seq;
    }

    private void rejectV2Write(UUID runId) {
        Integer version = jdbc.queryForObject(
                "SELECT protocol_version FROM writing_v2.agent_run WHERE run_id=?",
                Integer.class, runId);
        if (Integer.valueOf(2).equals(version))
            throw new com.writingagent.v2.runtime.RunContractException("V2_WRITE_REQUIRES_JOURNAL");
    }

    @Override
    public int countEvents(UUID runId, String eventType) {
        Integer n =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)::int FROM writing_v2.run_event
                        WHERE run_id = ? AND event_type = ?
                        """,
                        Integer.class,
                        runId,
                        eventType);
        return n == null ? 0 : n;
    }

    @Override
    public Long findTraceSequence(UUID runId, String correlationId) {
        var rows = jdbc.queryForList(
                "SELECT seq FROM writing_v2.run_event WHERE run_id=? AND correlation_id=? ORDER BY seq LIMIT 1",
                Long.class, runId, correlationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public java.util.List<RunEventView> listExecutionEvents(UUID rootRunId) {
        return jdbc.query("""
                WITH RECURSIVE runs AS (
                  SELECT run_id, parent_run_id FROM writing_v2.agent_run WHERE run_id=?
                  UNION ALL
                  SELECT r.run_id, r.parent_run_id FROM writing_v2.agent_run r
                    JOIN runs p ON r.parent_run_id=p.run_id
                )
                SELECT e.*, r.parent_run_id, a.operation FROM writing_v2.run_event e
                  JOIN runs r ON r.run_id=e.run_id
                  JOIN writing_v2.agent_run ar ON ar.run_id=r.run_id
                  JOIN writing_v2.action_ledger a ON a.action_id=ar.action_id
                ORDER BY e.run_id, e.seq
                """, (rs, n) -> new RunEventView(
                        (UUID) rs.getObject("run_id"), (UUID) rs.getObject("parent_run_id"), rs.getString("operation"),
                        rs.getLong("seq"), rs.getString("event_type"),
                        rs.getString("tool_call_id"), rs.getString("summary"),
                        rs.getTimestamp("created_at").toInstant()), rootRunId);
    }

    @Override
    public java.util.List<ExecutionView> listExecutions(UUID projectId, UUID conversationId) {
        return jdbc.query("""
                SELECT a.client_action_id, a.run_id, a.conversation_id, a.operation,
                       a.status, a.error_code, a.created_at
                FROM writing_v2.action_ledger a JOIN writing_v2.agent_run r ON r.run_id=a.run_id
                WHERE a.project_id=? AND a.conversation_id=? AND r.parent_run_id IS NULL
                ORDER BY a.created_at DESC LIMIT 50
                """, (rs,n) -> new ExecutionView(rs.getString("client_action_id"),
                (UUID) rs.getObject("run_id"), (UUID) rs.getObject("conversation_id"),
                rs.getString("operation"), rs.getString("status"), rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant()), projectId, conversationId);
    }
}

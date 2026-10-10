package com.writingagent.v2.infrastructure.jdbc;

import com.writingagent.v2.application.port.ActionLedgerPort;
import com.writingagent.v2.domain.ActionStatus;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcActionLedgerAdapter implements ActionLedgerPort {

    private final JdbcTemplate jdbc;

    public JdbcActionLedgerAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryInsertClaim(
            UUID actionId,
            UUID projectId,
            String clientActionId,
            String requestDigest,
            String operation) {
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO writing_v2.action_ledger(
                          action_id, project_id, client_action_id, request_digest, operation, status,
                          workspace_id, scope_key)
                        VALUES (?, ?, ?, ?, ?, ?,
                          (SELECT workspace_id FROM writing_v2.writing_project WHERE project_id = ?),
                          'project:' || CAST(? AS TEXT))
                        ON CONFLICT (project_id, client_action_id) DO NOTHING
                        """,
                        actionId,
                        projectId,
                        clientActionId,
                        requestDigest,
                        operation,
                        ActionStatus.CLAIMED.name(),
                        projectId,
                        projectId);
        return inserted == 1;
    }

    @Override
    public Optional<ClaimRow> findClaimKeys(UUID projectId, String clientActionId) {
        try {
            Map<String, Object> row =
                    jdbc.queryForMap(
                            """
                            SELECT action_id, request_digest, status, run_id
                            FROM writing_v2.action_ledger
                            WHERE project_id = ? AND client_action_id = ?
                            """,
                            projectId,
                            clientActionId);
            return Optional.of(
                    new ClaimRow(
                            (UUID) row.get("action_id"),
                            (String) row.get("request_digest"),
                            ActionStatus.valueOf((String) row.get("status")),
                            (UUID) row.get("run_id")));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ActionLedgerView> find(UUID projectId, String clientActionId) {
        try {
            Map<String, Object> row =
                    jdbc.queryForMap(
                            """
                            SELECT conversation_id, task_id, run_id, status, plan_revision_id, result_may_exist,
                                   result_snapshot_json, result_assistant_message_id, error_code
                            FROM writing_v2.action_ledger
                            WHERE project_id = ? AND client_action_id = ?
                            """,
                            projectId,
                            clientActionId);
            return Optional.of(mapView(row));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public java.util.List<ChapterActionRow> listChapterActions(UUID projectId) {
        java.util.List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT action_id, client_action_id, operation, status, error_code, run_id
                        FROM writing_v2.action_ledger
                        WHERE project_id = ?
                          AND operation IN ('GENERATE_CHAPTER', 'REWRITE_CHAPTER')
                        ORDER BY updated_at DESC
                        LIMIT 100
                        """,
                        projectId);
        java.util.List<ChapterActionRow> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(
                    new ChapterActionRow(
                            (UUID) row.get("action_id"),
                            (String) row.get("client_action_id"),
                            (String) row.get("operation"),
                            (String) row.get("status"),
                            (String) row.get("error_code"),
                            (UUID) row.get("run_id")));
        }
        return out;
    }

    private static ActionLedgerView mapView(Map<String, Object> row) {
        return new ActionLedgerView(
                (UUID) row.get("conversation_id"),
                (UUID) row.get("task_id"),
                (UUID) row.get("run_id"),
                (String) row.get("status"),
                (UUID) row.get("plan_revision_id"),
                Boolean.TRUE.equals(row.get("result_may_exist")),
                (String) row.get("result_snapshot_json"),
                (UUID) row.get("result_assistant_message_id"),
                (String) row.get("error_code"));
    }

    @Override
    public void bindTaskRun(UUID actionId, UUID conversationId, UUID taskId, UUID runId) {
        jdbc.update(
                """
                UPDATE writing_v2.action_ledger
                SET conversation_id = ?, task_id = ?, run_id = ?, updated_at = NOW()
                WHERE action_id = ?
                """,
                conversationId,
                taskId,
                runId,
                actionId);
    }

    @Override
    public void markResult(
            UUID actionId,
            ActionStatus status,
            UUID planRevisionId,
            boolean resultMayExist,
            String errorCode) {
        markResult(actionId, status, planRevisionId, resultMayExist, errorCode, null, null);
    }

    @Override
    public void markResult(
            UUID actionId,
            ActionStatus status,
            UUID planRevisionId,
            boolean resultMayExist,
            String errorCode,
            String resultSnapshotJson,
            UUID resultAssistantMessageId) {
        jdbc.update(
                """
                UPDATE writing_v2.action_ledger
                SET status = ?,
                    plan_revision_id = ?,
                    result_may_exist = ?,
                    error_code = ?,
                    result_snapshot_json = COALESCE(?, result_snapshot_json),
                    result_assistant_message_id = COALESCE(?, result_assistant_message_id),
                    updated_at = NOW()
                WHERE action_id = ?
                """,
                status.name(),
                planRevisionId,
                resultMayExist,
                errorCode,
                resultSnapshotJson,
                resultAssistantMessageId,
                actionId);
    }

    @Override
    public java.util.List<ChapterActionRow> listChildToolActions(UUID parentRunId) {
        if (parentRunId == null) {
            return java.util.List.of();
        }
        String prefix = "tool|" + parentRunId + "|";
        java.util.List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT action_id, client_action_id, operation, status, error_code, run_id
                        FROM writing_v2.action_ledger
                        WHERE client_action_id LIKE ?
                        ORDER BY updated_at DESC
                        LIMIT 50
                        """,
                        prefix + "%");
        java.util.List<ChapterActionRow> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(
                    new ChapterActionRow(
                            (UUID) row.get("action_id"),
                            (String) row.get("client_action_id"),
                            (String) row.get("operation"),
                            (String) row.get("status"),
                            (String) row.get("error_code"),
                            (UUID) row.get("run_id")));
        }
        return out;
    }
}

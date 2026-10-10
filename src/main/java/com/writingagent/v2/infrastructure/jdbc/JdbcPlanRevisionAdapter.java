package com.writingagent.v2.infrastructure.jdbc;

import com.writingagent.v2.application.port.PlanRevisionPort;
import com.writingagent.v2.domain.PlanRevisionStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPlanRevisionAdapter implements PlanRevisionPort {

    private final JdbcTemplate jdbc;

    public JdbcPlanRevisionAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long nextRevisionNo(UUID projectId) {
        Long next =
                jdbc.queryForObject(
                        """
                        SELECT COALESCE(MAX(revision_no), 0) + 1
                        FROM writing_v2.plan_revision WHERE project_id = ?
                        """,
                        Long.class,
                        projectId);
        return next == null ? 1L : next;
    }

    @Override
    public void insertDraft(
            UUID planRevisionId,
            UUID projectId,
            long revisionNo,
            String payloadJson,
            String contentHash,
            String sourceBasis) {
        jdbc.update(
                """
                INSERT INTO writing_v2.plan_revision(
                  plan_revision_id, project_id, revision_no, status, payload_json, content_hash, source_basis)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                planRevisionId,
                projectId,
                revisionNo,
                PlanRevisionStatus.DRAFT.name(),
                payloadJson,
                contentHash,
                sourceBasis);
    }

    @Override
    public PlanRevisionView findById(UUID planRevisionId) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        """
                        SELECT status, revision_no, payload_json, content_hash, confirmed_at
                        FROM writing_v2.plan_revision WHERE plan_revision_id = ?
                        """,
                        planRevisionId);
        Object payload = row.get("payload_json");
        return new PlanRevisionView(
                (String) row.get("status"),
                ((Number) row.get("revision_no")).longValue(),
                payload == null ? "" : payload.toString(),
                (String) row.get("content_hash"),
                toInstant(row.get("confirmed_at")));
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        throw new IllegalStateException("Unsupported confirmed_at type: " + value.getClass());
    }

    @Override
    public void markSuperseded(UUID planRevisionId) {
        jdbc.update(
                """
                UPDATE writing_v2.plan_revision SET status = ? WHERE plan_revision_id = ?
                """,
                PlanRevisionStatus.SUPERSEDED.name(),
                planRevisionId);
    }

    @Override
    public int confirmDraftIfMatches(
            UUID planRevisionId, long expectedRevisionNo, String confirmedBy) {
        return jdbc.update(
                """
                UPDATE writing_v2.plan_revision
                SET status = ?, confirmed_at = NOW(), confirmed_by = ?
                WHERE plan_revision_id = ?
                  AND revision_no = ?
                  AND status = ?
                """,
                PlanRevisionStatus.CONFIRMED.name(),
                confirmedBy,
                planRevisionId,
                expectedRevisionNo,
                PlanRevisionStatus.DRAFT.name());
    }
}

package com.writingagent.v2.application.port;

import java.time.Instant;
import java.util.UUID;

/** Persistence port for plan_revision rows and atomic confirm CAS. */
public interface PlanRevisionPort {

    /**
     * Typed plan revision for application consumers. {@code status} remains a stored string
     * (DRAFT/CONFIRMED/SUPERSEDED).
     */
    record PlanRevisionView(
            String status,
            long revisionNo,
            String payloadJson,
            String contentHash,
            Instant confirmedAt) {}

    long nextRevisionNo(UUID projectId);

    void insertDraft(
            UUID planRevisionId,
            UUID projectId,
            long revisionNo,
            String payloadJson,
            String contentHash,
            String sourceBasis);

    PlanRevisionView findById(UUID planRevisionId);

    void markSuperseded(UUID planRevisionId);

    /**
     * Atomic CAS: DRAFT + matching revision_no → CONFIRMED. Returns updated row count (0 or 1).
     */
    int confirmDraftIfMatches(UUID planRevisionId, long expectedRevisionNo, String confirmedBy);
}

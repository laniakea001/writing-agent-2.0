package com.writingagent.v2.application.port;

import com.writingagent.v2.domain.ActionStatus;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for action ledger claim / bind / result. No SQL here. */
public interface ActionLedgerPort {

    record ClaimRow(
            UUID actionId, String requestDigest, ActionStatus status, UUID runId) {}

    /**
     * Typed ledger row for application consumers. {@code status} is the stored status string
     * (e.g. CLAIMED); not an enum at the port boundary for this remediation.
     */
    record ActionLedgerView(
            UUID conversationId,
            UUID taskId,
            UUID runId,
            String status,
            UUID planRevisionId,
            boolean resultMayExist,
            String resultSnapshotJson,
            UUID resultAssistantMessageId,
            String errorCode) {}

    /** INSERT … ON CONFLICT DO NOTHING; returns empty if conflict (caller loads existing). */
    boolean tryInsertClaim(
            UUID actionId,
            UUID projectId,
            String clientActionId,
            String requestDigest,
            String operation);

    Optional<ClaimRow> findClaimKeys(UUID projectId, String clientActionId);

    Optional<ActionLedgerView> find(UUID projectId, String clientActionId);

    /**
     * Recent GENERATE_CHAPTER / REWRITE_CHAPTER rows for progress projection (CLAIMED / FAILED /
     * RESULT_RECORDED).
     */
    java.util.List<ChapterActionRow> listChapterActions(UUID projectId);

    record ChapterActionRow(
            UUID actionId,
            String clientActionId,
            String operation,
            String status,
            String errorCode,
            UUID runId) {}

    void bindTaskRun(UUID actionId, UUID conversationId, UUID taskId, UUID runId);

    void markResult(
            UUID actionId,
            ActionStatus status,
            UUID planRevisionId,
            boolean resultMayExist,
            String errorCode);

    /** Persist terminal status plus fixed REPLAY projection snapshot (root ASSISTANT_TURN). */
    void markResult(
            UUID actionId,
            ActionStatus status,
            UUID planRevisionId,
            boolean resultMayExist,
            String errorCode,
            String resultSnapshotJson,
            UUID resultAssistantMessageId);

    /** Child Tool actions keyed as {@code tool|{parentRunId}|...}. */
    java.util.List<ChapterActionRow> listChildToolActions(UUID parentRunId);
}

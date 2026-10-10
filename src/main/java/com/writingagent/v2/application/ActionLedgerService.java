package com.writingagent.v2.application;

import com.writingagent.v2.application.port.ActionLedgerPort;
import com.writingagent.v2.application.support.RequestDigests;
import com.writingagent.v2.domain.ActionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActionLedgerService {

    public enum ClaimKind {
        WINNER,
        IDEMPOTENT_REPLAY,
        DIGEST_CONFLICT
    }

    public record ClaimResult(
            ClaimKind kind, UUID actionId, ActionStatus status, String requestDigest, UUID runId) {}

    private final ActionLedgerPort ledger;

    public ActionLedgerService(ActionLedgerPort ledger) {
        this.ledger = ledger;
    }

    @Transactional
    public ClaimResult claim(
            UUID projectId, String clientActionId, String requestDigest, String operation) {
        UUID actionId = UUID.randomUUID();
        if (ledger.tryInsertClaim(actionId, projectId, clientActionId, requestDigest, operation)) {
            return new ClaimResult(ClaimKind.WINNER, actionId, ActionStatus.CLAIMED, requestDigest, null);
        }
        ActionLedgerPort.ClaimRow row =
                ledger.findClaimKeys(projectId, clientActionId)
                        .orElseThrow(() -> new IllegalStateException("claim conflict without row"));
        if (!row.requestDigest().equals(requestDigest)) {
            return new ClaimResult(
                    ClaimKind.DIGEST_CONFLICT,
                    row.actionId(),
                    row.status(),
                    row.requestDigest(),
                    row.runId());
        }
        return new ClaimResult(
                ClaimKind.IDEMPOTENT_REPLAY,
                row.actionId(),
                row.status(),
                row.requestDigest(),
                row.runId());
    }

    public Optional<ActionLedgerPort.ActionLedgerView> find(UUID projectId, String clientActionId) {
        return ledger.find(projectId, clientActionId);
    }

    public List<ActionLedgerPort.ChapterActionRow> listChapterActions(UUID projectId) {
        return ledger.listChapterActions(projectId);
    }

    public List<ActionLedgerPort.ChapterActionRow> listChildToolActions(UUID parentRunId) {
        return ledger.listChildToolActions(parentRunId);
    }

    public void bindTaskRun(UUID actionId, UUID conversationId, UUID taskId, UUID runId) {
        ledger.bindTaskRun(actionId, conversationId, taskId, runId);
    }

    public void markResult(
            UUID actionId,
            ActionStatus status,
            UUID planRevisionId,
            boolean resultMayExist,
            String errorCode) {
        ledger.markResult(actionId, status, planRevisionId, resultMayExist, errorCode);
    }

    public void markResult(
            UUID actionId,
            ActionStatus status,
            UUID planRevisionId,
            boolean resultMayExist,
            String errorCode,
            String resultSnapshotJson,
            UUID resultAssistantMessageId) {
        ledger.markResult(
                actionId,
                status,
                planRevisionId,
                resultMayExist,
                errorCode,
                resultSnapshotJson,
                resultAssistantMessageId);
    }

    public static String digestFor(
            UUID projectId,
            UUID conversationId,
            UUID taskId,
            String operation,
            String message,
            Long expectedProjectVersion) {
        return RequestDigests.of(
                projectId.toString(),
                conversationId == null ? null : conversationId.toString(),
                taskId == null ? null : taskId.toString(),
                operation,
                message,
                expectedProjectVersion);
    }
}

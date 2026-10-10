package com.writingagent.v2.runtime;

import com.writingagent.v2.domain.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable run facts. Implementations must join the caller's business transaction. */
public interface RunJournal {
    record Scope(UUID workspaceId, UUID projectId, UUID conversationId, UUID actionId,
                 String clientActionId, String userId, String operation) {}
    record Run(UUID runId, UUID rootRunId, UUID parentRunId, String parentToolCallId,
               int protocolVersion, RunStatus status, long latestSequence, Long terminalSequence,
               Map<UUID, Long> terminalManifest) {
        public boolean terminal() { return terminalSequence != null; }
    }

    /** Payload is a public allowlisted projection, never raw SDK messages or prompts. */
    record Draft(String producerEventKey, String rawEventType, String outputType,
                 String toolCallId, UUID invocationId, boolean publishRequired,
                 Map<String, Object> safePayload) {}

    record Event(UUID eventId, UUID runId, UUID rootRunId, UUID parentRunId,
                 String parentToolCallId, long sequence, String producerEventKey,
                 String rawEventType, String outputType, String toolCallId, UUID invocationId,
                 boolean publishRequired, Map<String, Object> safePayload, Instant createdAt) {}

    Run create(UUID taskId, UUID actionId, UUID parentRunId, String parentToolCallId);
    long append(UUID runId, Draft event);
    /** Locks root then source; invoke before any generated business artifact is saved. */
    void guardBusinessCommit(UUID runId);
    Run settle(UUID runId, RunStatus terminalStatus, int modelRequests,
               String outcomeCode, boolean resultMayExist);
    Run get(UUID runId);
    Scope scope(UUID runId);
    List<Run> tree(UUID rootRunId);
    /** At most 256 rows. Includes internal events so replay can advance through them. */
    List<Event> page(UUID runId, long afterSequence, int limit);
}

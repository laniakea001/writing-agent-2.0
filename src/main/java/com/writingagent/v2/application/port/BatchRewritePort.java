package com.writingagent.v2.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Durable batch seam. All mutation methods participate in the caller's transaction. */
public interface BatchRewritePort {
 enum Status { WAITING_REOPEN,RUNNING,WAITING_CHAPTER_CONFIRMATION,PAUSED,UNKNOWN,COMPLETED,COMPLETED_WITH_SKIPS,STOPPED }
 enum ItemStatus { PENDING,GENERATING,WAITING_CONFIRMATION,APPLIED,REJECTED,FAILED,UNKNOWN }
 record Item(UUID itemId,int ordinal,UUID chapterKey,int chapterIndex,UUID initialRevisionId,ItemStatus status,UUID runId,UUID proposalId) {}
 record Batch(UUID batchId,UUID workspaceId,UUID projectId,UUID conversationId,String origin,String digest,
              String instruction,Status status,long version,JsonNode manifest,List<Item> items,UUID reopenProposalId) {}
 Optional<Batch> lookup(UUID projectId,String clientActionId);
 Optional<Batch> get(UUID batchId);
 Optional<Batch> active(UUID projectId);
 boolean hasActive(UUID projectId);
 Batch insert(UUID workspaceId,UUID projectId,String clientActionId,String digest,String instruction,Status status,JsonNode manifest,List<Item> items);
 void bindCandidate(UUID batchId,UUID itemId,UUID proposalId,UUID runId);
 void guardDecision(UUID proposalId);
 void decisionRecorded(UUID proposalId,String proposalStatus,JsonNode receipt,JsonNode currentManifest);
 void bindReopen(UUID batchId,UUID proposalId);
 void attachConversation(UUID batchId,UUID conversationId);
 void claimGeneration(UUID batchId,UUID itemId,UUID runId);
 void activate(UUID batchId);
 List<UUID> dispatchableIds();
 void generationFailed(UUID batchId,UUID runId,boolean uncertain);
 void bindAgentContext(UUID batchId,UUID conversationId,UUID sourceRunId);
 Optional<JsonNode> controlReceipt(UUID batchId,String key,String digest);
 void recordControl(UUID batchId,String key,String digest,Status next,JsonNode receipt);
 boolean decisionBlocked(UUID proposalId);
 void pauseChanged(UUID batchId);
 boolean acceptsCandidate(UUID projectId,String key,UUID runId);
 boolean ownsProposal(UUID projectId,UUID proposalId);
 record GenerationOwner(UUID batchId,UUID runId,long pid) {}
 Optional<GenerationOwner> generationOwner(UUID batchId);
 List<GenerationOwner> activeGenerationOwners();
}

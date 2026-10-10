package com.writingagent.v2.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence seam for versioned documents and decisions; all calls run in application transactions. */
public interface WritingDocumentPort {
    record Project(UUID id,UUID workspaceId,String ownerScope,String title,long version,UUID planId,UUID articleId) {}
    record Plan(UUID id,UUID projectId,long revision,String status,JsonNode payload,String sourceBasis,String hash,Instant confirmedAt) {}
    record Article(UUID id,UUID projectId,UUID planId,long version,String status,int chapterCount,UUID metadataId,JsonNode completionManifest) {}
    record Chapter(UUID key,int index,String title,String brief,UUID revisionId,long revision,JsonNode contentJson,String markdown,String documentHash,String contentHash,String sourceBasis) {}
    record Action(UUID id,String digest,String status,JsonNode receipt,boolean claimed) {}
    record Proposal(UUID id,UUID workspaceId,UUID projectId,String requestedBy,String origin,String type,String status,UUID planId,long projectVersion,Long articleVersion,JsonNode snapshot,Instant expiresAt,JsonNode receipt,UUID conversationId,UUID sourceRunId) {}

    Project project(UUID id,boolean lock);
    List<Project> projects(UUID workspaceId,String ownerScope);
    String workspaceOwner(UUID workspaceId);
    Optional<Plan> plan(UUID id);
    Optional<Article> article(UUID projectId,boolean lock);
    List<Chapter> chapters(UUID articleId);
    JsonNode metadata(UUID metadataId);
    Action claim(UUID workspaceId,UUID projectId,String key,String digest,String operation);
    Optional<Action> action(UUID workspaceId,UUID projectId,String key);
    void settle(UUID actionId,JsonNode receipt,boolean success);
    Plan insertPlan(UUID projectId,JsonNode payload,String hash,String sourceBasis);
    void confirmPlan(UUID planId,String userId);
    void syncArticle(Project project,Plan plan);
    Chapter saveChapter(Article article,Chapter chapter,UUID actionId,JsonNode doc,String markdown,String documentHash,String contentHash,String sourceBasis);
    void refreshArticle(UUID articleId,int nonEmptyCount,String status);
    UUID saveMetadata(UUID articleId,JsonNode payload,UUID actionId,String sourceBasis);
    void complete(UUID articleId,JsonNode manifest);
    void reopen(UUID articleId,String status,int nonEmptyCount);
    Proposal insertProposal(Project project,Article article,UUID planId,String type,String origin,String userId,JsonNode snapshot,UUID conversationId,UUID sourceRunId);
    boolean validProposalContext(UUID workspaceId,UUID projectId,UUID conversationId,UUID sourceRunId);
    List<UUID> conversationProposals(UUID conversationId,int offset,int limit);
    List<UUID> approvedRewriteContinuations();
    Optional<Proposal> proposal(UUID id,boolean lock);
    void decideProposal(UUID id,String status,UUID actionId,JsonNode receipt);
}

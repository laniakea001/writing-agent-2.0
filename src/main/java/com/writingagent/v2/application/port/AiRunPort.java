package com.writingagent.v2.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** Registration and admission only; business artifacts still belong to WritingCapability. */
public interface AiRunPort {
    record Conversation(UUID workspaceId,UUID conversationId,UUID projectId,long contextVersion) {}
    record Execution(UUID workspaceId,UUID projectId,UUID conversationId,UUID actionId,UUID taskId,UUID runId,
                     String clientActionId,String digest,JsonNode request,JsonNode receipt,boolean winner) {}
    UUID conversation(UUID workspaceId,UUID projectId);
    Conversation createConversation(UUID workspaceId);
    Conversation conversationInfo(UUID conversationId);
    record ConversationSummary(UUID workspaceId,UUID conversationId,UUID projectId,String title,String createdAt) {}
    List<ConversationSummary> conversations(UUID workspaceId,int offset,int limit);
    void selectProject(UUID conversationId,UUID projectId);
    Execution register(UUID workspaceId,UUID projectId,UUID conversationId,String key,String digest,JsonNode request);
    Execution get(UUID runId);
    Execution lookup(UUID projectId,String clientActionId);
    Execution lookupConversation(UUID conversationId,String clientActionId);
    Execution registerChild(Execution parent,UUID projectId,String toolCallId,String toolName,JsonNode request);
    List<Execution> list(UUID workspaceId);
    void queued(UUID runId);
    void running(UUID runId,UUID ownerId);
    void finish(Execution execution,JsonNode receipt,boolean success,boolean uncertain);
    /** Separate fixed audit receipt; never alters the original terminal run/result. */
    JsonNode reconcileTerminal(Execution execution,String clientActionId,JsonNode snapshot);
}

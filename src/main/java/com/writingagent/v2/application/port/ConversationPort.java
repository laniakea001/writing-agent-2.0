package com.writingagent.v2.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence port for conversation messages beyond project bootstrap. */
public interface ConversationPort {

    record MessageView(UUID messageId, String role, String body, Instant createdAt, UUID sourceRunId) {
        public MessageView(UUID messageId, String role, String body, Instant createdAt) {
            this(messageId, role, body, createdAt, null);
        }
    }

    void insertUserMessage(UUID messageId, UUID conversationId, String body);

    void insertAssistantMessage(UUID messageId, UUID conversationId, String bodyJson);

    void insertAssistantMessage(UUID messageId, UUID conversationId, String bodyJson, UUID sourceRunId);

    List<MessageView> listMessages(UUID conversationId);

    boolean belongsToProject(UUID conversationId, UUID projectId);
}

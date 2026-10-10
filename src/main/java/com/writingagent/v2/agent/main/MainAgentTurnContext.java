package com.writingagent.v2.agent.main;

import java.util.UUID;

/** Per-turn server-bound context for Main Agent tools (not taken from model args). */
public final class MainAgentTurnContext {

    private final UUID projectId;
    private final UUID conversationId;
    private final UUID parentActionId;
    private final UUID parentRunId;
    private final String userId;
    private String userMessage = "";
    private final java.util.List<java.util.Map<String, Object>> receipts = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void bindUserMessage(String message) { userMessage = message == null ? "" : message; }
    public WritingTurnPolicy policy() { return new WritingTurnPolicy(userMessage); }
    public void recordReceipt(java.util.Map<String, Object> receipt) {
        receipts.add(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(receipt)));
    }
    public java.util.List<java.util.Map<String, Object>> receipts() { return java.util.List.copyOf(receipts); }

    public MainAgentTurnContext(
            UUID projectId,
            UUID conversationId,
            UUID parentActionId,
            UUID parentRunId,
            String userId) {
        this.projectId = projectId;
        this.conversationId = conversationId;
        this.parentActionId = parentActionId;
        this.parentRunId = parentRunId;
        this.userId = userId == null ? "local-user" : userId;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public UUID parentActionId() {
        return parentActionId;
    }

    public UUID parentRunId() {
        return parentRunId;
    }

    public String userId() {
        return userId;
    }

    public static String childClientActionId(UUID parentRunId, String toolCallId, String toolName) {
        return "tool|" + parentRunId + "|" + toolCallId + "|" + toolName;
    }
}

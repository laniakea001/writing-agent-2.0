package com.writingagent.v2.application.generation;

import java.util.Objects;
import java.util.UUID;

/**
 * Shared command for plan / chapter generation modules (button and Tool callers).
 */
public record GenerationCommand(
        UUID projectId,
        UUID conversationId,
        String clientActionId,
        String userMessage,
        MessagePolicy messages,
        UUID parentRunId,
        Integer chapterIndex,
        String rewriteInstruction,
        String parentToolCallId) {

    public GenerationCommand(UUID projectId, UUID conversationId, String clientActionId,
            String userMessage, MessagePolicy messages, UUID parentRunId,
            Integer chapterIndex, String rewriteInstruction) {
        this(projectId, conversationId, clientActionId, userMessage, messages, parentRunId,
                chapterIndex, rewriteInstruction, null);
    }

    public GenerationCommand withParentToolCallId(String toolCallId) {
        if (parentRunId == null || toolCallId == null || toolCallId.isBlank())
            throw new IllegalArgumentException("Trusted parent run and real tool call ID required");
        return new GenerationCommand(projectId, conversationId, clientActionId, userMessage,
                messages, parentRunId, chapterIndex, rewriteInstruction, toolCallId);
    }

    public GenerationCommand {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(clientActionId, "clientActionId");
        Objects.requireNonNull(messages, "messages");
        if (userMessage == null) {
            userMessage = "";
        }
    }

    public static GenerationCommand plan(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            String userMessage,
            MessagePolicy messages,
            UUID parentRunId) {
        return new GenerationCommand(
                projectId, conversationId, clientActionId, userMessage, messages, parentRunId, null, null);
    }

    public static GenerationCommand chapter(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            int chapterIndex,
            String userMessage,
            MessagePolicy messages,
            UUID parentRunId) {
        return new GenerationCommand(
                projectId,
                conversationId,
                clientActionId,
                userMessage,
                messages,
                parentRunId,
                chapterIndex,
                null);
    }

    public static GenerationCommand rewrite(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            int chapterIndex,
            String instruction,
            MessagePolicy messages,
            UUID parentRunId) {
        return new GenerationCommand(
                projectId,
                conversationId,
                clientActionId,
                instruction == null ? "" : instruction,
                messages,
                parentRunId,
                chapterIndex,
                instruction);
    }

    public PresentationSink sink() {
        return messages.sink();
    }
}

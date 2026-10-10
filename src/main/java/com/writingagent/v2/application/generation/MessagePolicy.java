package com.writingagent.v2.application.generation;

import java.util.Objects;

/**
 * Typed presentation policy for shared generation modules (D0).
 * Modules never write conversation chat; outer entry owns product messages.
 */
public record MessagePolicy(InvocationOrigin origin) {

    public MessagePolicy {
        Objects.requireNonNull(origin, "origin");
    }

    public static MessagePolicy workbenchButton() {
        return new MessagePolicy(InvocationOrigin.WORKBENCH_BUTTON);
    }

    public static MessagePolicy mainAgentTool() {
        return new MessagePolicy(InvocationOrigin.MAIN_AGENT_TOOL);
    }

    /**
     * Button path: module records the single user message after claim wins (before model).
     * Main-agent Tool path: root turn already recorded the user utterance.
     */
    public boolean recordUserMessageAfterClaim() {
        return origin == InvocationOrigin.WORKBENCH_BUTTON;
    }

    /** Plan clarification assistant chat inside PlanPersistence. */
    public boolean persistAssistantChatMessage() {
        return origin == InvocationOrigin.WORKBENCH_BUTTON;
    }

    public PresentationSink sink() {
        return persistAssistantChatMessage()
                ? PresentationSink.CHAT_AND_ARTIFACT
                : PresentationSink.ARTIFACT_ONLY;
    }
}

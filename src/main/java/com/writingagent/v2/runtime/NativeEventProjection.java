package com.writingagent.v2.runtime;

import io.agentscope.core.event.*;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Explicit allowlist: never serialize SDK events, prompt text, tool bodies or thinking. */
public final class NativeEventProjection {
    private NativeEventProjection() {}
    public static RunJournal.Draft project(UUID invocation, String role, AgentEvent event) {
        String type = "INTERNAL", callId = null;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("agentRole", role);
        if (event instanceof ModelCallStartEvent) type = "MODEL_STARTED";
        else if (event instanceof ModelCallEndEvent) type = "MODEL_FINISHED";
        else if (event instanceof ToolCallStartEvent tool) {
            type = "TOOL_CALL_STARTED"; callId = tool.getToolCallId();
            payload.put("toolName", tool.getToolCallName());
        } else if (event instanceof ToolCallEndEvent tool) {
            type = "TOOL_CALL"; callId = tool.getToolCallId();
            payload.put("toolName", tool.getToolCallName());
        } else if (event instanceof ToolResultEndEvent tool) {
            type = "TOOL_FINISHED"; callId = tool.getToolCallId();
            payload.put("toolName", tool.getToolCallName());
            payload.put("resultStatus", tool.getState().name());
        }
        if (type.startsWith("TOOL") && (callId == null || callId.isBlank()))
            throw new RunContractException("TOOL_CALL_ID_MISSING");
        // Phases have one durable fact per real call. Other events use the SDK event identity.
        String key = invocation + ":" + (callId == null ? event.getId() : type + ":" + callId);
        return new RunJournal.Draft(key, event.getType().name(), type, callId,
                invocation, !"INTERNAL".equals(type), payload);
    }
}

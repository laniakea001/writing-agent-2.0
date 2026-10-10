package com.writingagent.v2.runtime;

import static org.junit.jupiter.api.Assertions.*;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeEventProjectionTest {
    @Test void nativeToolPhasesKeepRealIdentityAndAgentEndIsNotPlatformSuccess() {
        UUID invocation = UUID.randomUUID();
        var started = NativeEventProjection.project(invocation, "PlanAgent",
                new ToolCallStartEvent("reply", "real-call-17", "read_current_project_context"));
        var ended = NativeEventProjection.project(invocation, "PlanAgent",
                new ToolResultEndEvent("reply", "real-call-17", "read_current_project_context", ToolResultState.SUCCESS));
        assertEquals("TOOL_CALL_STARTED", started.outputType());
        assertEquals("real-call-17", ended.toolCallId());
        assertEquals("SUCCESS", ended.safePayload().get("resultStatus"));
        var nativeEnd = NativeEventProjection.project(invocation, "PlanAgent", new AgentEndEvent("reply"));
        assertFalse(nativeEnd.publishRequired());
        assertNotEquals("RUN_TERMINAL", nativeEnd.outputType());
    }
}

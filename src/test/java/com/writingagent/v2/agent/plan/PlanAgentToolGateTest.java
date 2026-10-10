package com.writingagent.v2.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlanAgentToolGateTest {

    @Test
    void requiresModelRequestAfterToolResultNotJustToolRequest() {
        assertFalse(
                PlanAgentRuntime.traceHasModelAfterToolResult(
                        List.of(
                                new PlanAgentRuntime.TraceLine(1, "TOOL_REQUEST", "t"),
                                new PlanAgentRuntime.TraceLine(2, "MODEL_REQUEST", "n=2"))));
        assertTrue(
                PlanAgentRuntime.traceHasModelAfterToolResult(
                        List.of(
                                new PlanAgentRuntime.TraceLine(1, "TOOL_REQUEST", "t"),
                                new PlanAgentRuntime.TraceLine(
                                        2, "TOOL_RESULT", "sha256=abc when=tool_return"),
                                new PlanAgentRuntime.TraceLine(3, "MODEL_REQUEST", "n=2"))));
    }

    @Test
    void toolResultCallbackFiresWithToolReturnMarkerBeforeCallerContinues() {
        List<String> ordered = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        List<PlanAgentRuntime.TraceLine> lines = new ArrayList<>();
        // Simulate Runtime wiring: callback appends TOOL_RESULT synchronously when Tool returns.
        java.util.function.Consumer<String> onResult =
                detail -> {
                    ordered.add("TOOL_RESULT");
                    lines.add(
                            new PlanAgentRuntime.TraceLine(
                                    seq.incrementAndGet(), "TOOL_RESULT", detail));
                };
        onResult.accept("sha256=abc projectId=p1 invocations=1 when=tool_return");
        ordered.add("AFTER_TOOL_RETURN");
        ordered.add("MODEL_REQUEST");
        lines.add(new PlanAgentRuntime.TraceLine(seq.incrementAndGet(), "MODEL_REQUEST", "n=2"));

        assertEquals(List.of("TOOL_RESULT", "AFTER_TOOL_RETURN", "MODEL_REQUEST"), ordered);
        assertTrue(PlanAgentRuntime.traceHasModelAfterToolResult(lines));
        assertTrue(lines.get(0).detail().contains("when=tool_return"));
    }
}

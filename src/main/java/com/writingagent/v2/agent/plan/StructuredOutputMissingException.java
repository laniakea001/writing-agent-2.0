package com.writingagent.v2.agent.plan;

import java.util.List;

/**
 * Typed PlanAgent failure: structured output was expected but not present on the returned Msg.
 * Carries honest model-request count and redacted trace for ledger persistence.
 */
public final class StructuredOutputMissingException extends RuntimeException {

    private final int modelRequestCount;
    private final List<PlanAgentRuntime.TraceLine> trace;

    public StructuredOutputMissingException(
            String detail, int modelRequestCount, List<PlanAgentRuntime.TraceLine> trace) {
        super(detail == null ? "STRUCTURED_OUTPUT_MISSING" : detail);
        this.modelRequestCount = Math.max(0, modelRequestCount);
        this.trace = trace == null ? List.of() : List.copyOf(trace);
    }

    public int modelRequestCount() {
        return modelRequestCount;
    }

    public List<PlanAgentRuntime.TraceLine> trace() {
        return trace;
    }
}

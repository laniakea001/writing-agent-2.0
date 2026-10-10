package com.writingagent.v2.agent.plan;

import java.util.List;

/** Model/key/transport unavailable — distinct from structured-output contract failures. */
public final class ModelUnavailableException extends RuntimeException {

    private final int modelRequestCount;
    private final List<PlanAgentRuntime.TraceLine> trace;

    public ModelUnavailableException(
            String detail, int modelRequestCount, List<PlanAgentRuntime.TraceLine> trace) {
        super(detail == null ? "MODEL_UNAVAILABLE" : detail);
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

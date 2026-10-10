package com.writingagent.v2.agent.plan;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

final class PlanTraceMiddleware implements MiddlewareBase {

    private final AtomicInteger modelRequests;
    private final List<PlanAgentRuntime.TraceLine> lines;
    private final AtomicInteger seq;
    private final String businessToolName;

    PlanTraceMiddleware(
            AtomicInteger modelRequests,
            List<PlanAgentRuntime.TraceLine> lines,
            AtomicInteger seq,
            String businessToolName) {
        this.modelRequests = modelRequests;
        this.lines = lines;
        this.seq = seq;
        this.businessToolName = businessToolName;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        lines.add(
                new PlanAgentRuntime.TraceLine(
                        seq.incrementAndGet(),
                        "REASONING_START",
                        "msgs=" + (input.messages() == null ? 0 : input.messages().size())));
        return next.apply(input)
                .doOnComplete(
                        () ->
                                lines.add(
                                        new PlanAgentRuntime.TraceLine(
                                                seq.incrementAndGet(), "REASONING_END", "ok")));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        int n = modelRequests.incrementAndGet();
        lines.add(new PlanAgentRuntime.TraceLine(seq.incrementAndGet(), "MODEL_REQUEST", "n=" + n));
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        String names =
                input.toolCalls().stream()
                        .map(ToolUseBlock::getName)
                        .collect(Collectors.joining(","));
        lines.add(new PlanAgentRuntime.TraceLine(seq.incrementAndGet(), "TOOL_REQUEST", names));
        return next.apply(input)
                .doOnComplete(
                        () ->
                                lines.add(
                                        new PlanAgentRuntime.TraceLine(
                                                seq.incrementAndGet(), "TOOL_PHASE_END", names)));
    }
}

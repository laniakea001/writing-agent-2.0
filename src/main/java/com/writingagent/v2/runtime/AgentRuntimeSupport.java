package com.writingagent.v2.runtime;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Invocation-local observer used by both structured call and streaming call (one subscription). */
@Component
public class AgentRuntimeSupport {
    private final RunJournal journal;
    public AgentRuntimeSupport(RunJournal journal) { this.journal = journal; }
    public MiddlewareBase observer(UUID runId, String role) {
        if (runId == null) throw new RunContractException("RUN_ID_REQUIRED");
        UUID invocation = UUID.randomUUID();
        journal.scope(runId); // Identity comes from persisted platform registration, not model metadata.
        return new MiddlewareBase() {
            @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context,
                    AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
                return next.apply(input).doOnNext(event -> journal.append(runId,
                        NativeEventProjection.project(invocation, role, event)));
            }
        };
    }
    public static DashScopeChatModel model(String apiKey) {
        return DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus")
                .stream(false).enableThinking(false).formatter(new DashScopeChatFormatter()).build();
    }
}

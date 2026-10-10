package com.writingagent.v2.agent.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.generation.GenerationCommand;
import com.writingagent.v2.application.generation.MessagePolicy;
import com.writingagent.v2.application.generation.PlanGenerationModule;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** propose_writing_plan → {@link PlanGenerationModule} with MAIN_AGENT_TOOL policy. */
public final class ProposeWritingPlanTool extends ToolBase {

    public static final String TOOL_NAME = "propose_writing_plan";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final MainAgentTurnContext turn;
    private final PlanGenerationModule plans;

    public ProposeWritingPlanTool(MainAgentTurnContext turn, PlanGenerationModule plans) {
        super(
                ToolBase.builder()
                        .name(TOOL_NAME)
                        .description(
                                "Create or continue a writing PLAN_DRAFT from the project research"
                                        + " goal and user intent. Does NOT confirm the plan. Call"
                                        + " when the user asks to draft an outline/plan.")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "userIntent",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "Optional short intent; server already has"
                                                                + " the user turn text.")),
                                        "required",
                                        List.of()))
                        .readOnly(false)
                        .concurrencySafe(false));
        this.turn = turn;
        this.plans = plans;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String callId = com.writingagent.v2.agent.support.ToolCallIdentity.require(param);
        String intent =
                param.getInput() == null
                        ? ""
                        : String.valueOf(param.getInput().getOrDefault("userIntent", ""));
        String childId = MainAgentTurnContext.childClientActionId(turn.parentRunId(), callId, TOOL_NAME);
        return Mono.fromCallable(
                        () -> {
                            if (!turn.policy().allowPlanProposal()) {
                                Map<String, Object> denied = Map.of("tool", TOOL_NAME, "code", "PLAN_REPLACEMENT_NOT_REQUESTED");
                                turn.recordReceipt(denied);
                                return new ToolResultBlock(callId, TOOL_NAME,
                                        List.of(TextBlock.builder().text(JSON.writeValueAsString(denied)).build()));
                            }
                            Map<String, Object> result =
                                    plans.execute(
                                            GenerationCommand.plan(
                                                    turn.projectId(),
                                                    turn.conversationId(),
                                                    childId,
                                                    intent == null || "null".equals(intent)
                                                            ? "propose plan from research goal"
                                                            : intent,
                                                    MessagePolicy.mainAgentTool(),
                                                    turn.parentRunId()).withParentToolCallId(callId));
                            Map<String, Object> slim = new LinkedHashMap<>();
                            slim.put("tool", TOOL_NAME);
                            slim.put("childClientActionId", childId);
                            slim.put("actionId", result.get("actionId"));
                            slim.put("runId", result.get("runId"));
                            slim.put("outcome", result.get("outcome"));
                            slim.put("planRevisionId", result.get("planRevisionId"));
                            slim.put("code", result.get("code"));
                            slim.put("httpStatusHint", result.get("httpStatusHint"));
                            slim.put("modelRequestCount", result.get("modelRequestCount"));
                            slim.put("resultMayExist", result.get("resultMayExist"));
                            turn.recordReceipt(slim);
                            String text = JSON.writeValueAsString(slim);
                            return new ToolResultBlock(
                                    callId, TOOL_NAME, List.of(TextBlock.builder().text(text).build()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}

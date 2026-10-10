package com.writingagent.v2.agent.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.generation.ChapterGenerationModule;
import com.writingagent.v2.application.generation.GenerationCommand;
import com.writingagent.v2.application.generation.MessagePolicy;
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

public final class RewriteTargetChapterTool extends ToolBase {

    public static final String TOOL_NAME = "rewrite_target_chapter";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final MainAgentTurnContext turn;
    private final ChapterGenerationModule chapters;

    public RewriteTargetChapterTool(MainAgentTurnContext turn, ChapterGenerationModule chapters) {
        super(
                ToolBase.builder()
                        .name(TOOL_NAME)
                        .description(
                                "Rewrite exactly one chapter by 1-based chapterIndex using the user"
                                        + " instruction. Other chapters must remain unchanged.")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "chapterIndex",
                                                Map.of(
                                                        "type",
                                                        "integer",
                                                        "description",
                                                        "1-based chapter index"),
                                                "instruction",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "Rewrite instruction")),
                                        "required",
                                        List.of("chapterIndex", "instruction")))
                        .readOnly(false)
                        .concurrencySafe(false));
        this.turn = turn;
        this.chapters = chapters;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String callId = com.writingagent.v2.agent.support.ToolCallIdentity.require(param);
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> input =
                                    param.getInput() == null ? Map.of() : param.getInput();
                            int idx = ((Number) input.getOrDefault("chapterIndex", 0)).intValue();
                            String instruction = String.valueOf(input.getOrDefault("instruction", ""));
                            String childId =
                                    MainAgentTurnContext.childClientActionId(
                                            turn.parentRunId(), callId, TOOL_NAME);
                            Map<String, Object> result =
                                    chapters.rewrite(
                                            GenerationCommand.rewrite(
                                                    turn.projectId(),
                                                    turn.conversationId(),
                                                    childId,
                                                    idx,
                                                    instruction,
                                                    MessagePolicy.mainAgentTool(),
                                                    turn.parentRunId()).withParentToolCallId(callId));
                            Map<String, Object> slim = new LinkedHashMap<>();
                            slim.put("tool", TOOL_NAME);
                            slim.put("childClientActionId", childId);
                            slim.put("chapterIndex", idx);
                            slim.put("outcome", result.get("outcome"));
                            slim.put("actionId", result.get("actionId"));
                            slim.put("chapterRevisionId", result.get("chapterRevisionId"));
                            slim.put("code", result.get("code"));
                            slim.put("actionStatus", result.get("actionStatus"));
                            slim.put("resultMayExist", result.get("resultMayExist"));
                            slim.put("modelRequestCount", result.get("modelRequestCount"));
                            turn.recordReceipt(slim);
                            String text = JSON.writeValueAsString(slim);
                            return new ToolResultBlock(
                                    callId,
                                    TOOL_NAME,
                                    List.of(TextBlock.builder().text(text).build()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}

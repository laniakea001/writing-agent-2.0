package com.writingagent.v2.agent.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.ArticleApplicationService;
import com.writingagent.v2.application.ProjectApplicationService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class ReadWritingProgressTool extends ToolBase {

    public static final String TOOL_NAME = "read_writing_progress";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final MainAgentTurnContext turn;
    private final ArticleApplicationService articles;
    private final ProjectApplicationService projects;

    public ReadWritingProgressTool(
            MainAgentTurnContext turn,
            ArticleApplicationService articles,
            ProjectApplicationService projects) {
        super(
                ToolBase.builder()
                        .name(TOOL_NAME)
                        .description("Read article/chapter progress projection for the bound project.")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(),
                                        "required",
                                        List.of()))
                        .readOnly(true)
                        .concurrencySafe(true));
        this.turn = turn;
        this.articles = articles;
        this.projects = projects;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String callId = com.writingagent.v2.agent.support.ToolCallIdentity.require(param);
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> body = articles.articleProjection(turn.projectId());
                            body.put("projectId", turn.projectId().toString());
                            body.put("researchGoal", projects.currentResearchGoal(turn.projectId()));
                            String text = JSON.writeValueAsString(body);
                            return new ToolResultBlock(
                                    callId,
                                    TOOL_NAME,
                                    List.of(TextBlock.builder().text(text).build()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}

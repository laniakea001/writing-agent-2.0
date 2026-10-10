package com.writingagent.v2.agent.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.ArticleApplicationService;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.port.ArticlePort;
import com.writingagent.v2.application.port.PlanRevisionPort;
import com.writingagent.v2.domain.PlanRevisionStatus;
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

public final class ReadWritingProjectTool extends ToolBase {

    public static final String TOOL_NAME = "read_writing_project";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final MainAgentTurnContext turn;
    private final ProjectApplicationService projects;
    private final PlanRevisionPort plans;
    private final ArticlePort articles;

    public ReadWritingProjectTool(
            MainAgentTurnContext turn,
            ProjectApplicationService projects,
            PlanRevisionPort plans,
            ArticlePort articles) {
        super(
                ToolBase.builder()
                        .name(TOOL_NAME)
                        .description(
                                "Read current project title, research goal, plan status, and article"
                                        + " progress. Call before proposing plans or drafting"
                                        + " chapters.")
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
        this.projects = projects;
        this.plans = plans;
        this.articles = articles;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String callId = com.writingagent.v2.agent.support.ToolCallIdentity.require(param);
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> body = snapshot();
                            String text = JSON.writeValueAsString(body);
                            return new ToolResultBlock(callId, TOOL_NAME, List.of(TextBlock.builder().text(text).build()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Same current database projection is used by the Tool and by every new model invocation. */
    public Map<String, Object> snapshot() {
                            var project = projects.getProject(turn.projectId());
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("projectId", turn.projectId().toString());
                            body.put("title", project.title());
                            body.put("researchGoal", projects.currentResearchGoal(turn.projectId()));
                            body.put(
                                    "currentPlanRevisionId",
                                    project.currentPlanRevisionId() == null
                                            ? null
                                            : project.currentPlanRevisionId().toString());
                            if (project.currentPlanRevisionId() != null) {
                                PlanRevisionPort.PlanRevisionView plan =
                                        plans.findById(project.currentPlanRevisionId());
                                body.put("planStatus", plan.status());
                                body.put("planRevisionNo", plan.revisionNo());
                                body.put("planPayload", plan.payloadJson());
                                body.put(
                                        "planConfirmed",
                                        PlanRevisionStatus.CONFIRMED.name().equals(plan.status()));
                            } else {
                                body.put("planStatus", null);
                                body.put("planConfirmed", false);
                            }
                            articles
                                    .findByProjectId(turn.projectId())
                                    .ifPresent(
                                            a -> {
                                                body.put("articleId", a.articleId().toString());
                                                body.put("articleStatus", a.status());
                                                body.put("chapterCount", a.chapterCount());
                                                body.put("articlePlanRevisionId", a.planRevisionId().toString());
                                                body.put("planArticleAligned", a.planRevisionId().equals(project.currentPlanRevisionId()));
                                                body.put(
                                                        "completedChapterCount",
                                                        a.completedChapterCount());
                                            });
                            body.put(
                                    "note",
                                    "evidenceBasis remains USER_GOAL_AND_MODEL_SYNTHESIS; no"
                                            + " external retrieval.");
                            return body;
    }
}

package com.writingagent.v2.agent.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.ArticleApplicationService;
import com.writingagent.v2.application.generation.ChapterGenerationModule;
import com.writingagent.v2.application.generation.GenerationCommand;
import com.writingagent.v2.application.generation.MessagePolicy;
import com.writingagent.v2.application.port.ArticlePort;
import com.writingagent.v2.domain.ActionStatus;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Drafts all missing chapters for a confirmed outline. Model cannot shrink the required set via
 * chapterIndexes — indexes are ignored for skipping.
 */
public final class DraftConfirmedChaptersTool extends ToolBase {

    public static final String TOOL_NAME = "draft_confirmed_chapters";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final MainAgentTurnContext turn;
    private final ArticleApplicationService articles;
    private final ArticlePort articlePort;
    private final ChapterGenerationModule chapters;

    public DraftConfirmedChaptersTool(
            MainAgentTurnContext turn,
            ArticleApplicationService articles,
            ArticlePort articlePort,
            ChapterGenerationModule chapters) {
        super(
                ToolBase.builder()
                        .name(TOOL_NAME)
                        .description(
                                "After the plan is CONFIRMED by the user, generate missing chapter"
                                        + " bodies for every outline chapter that lacks CURRENT"
                                        + " body. Do not claim completion if chapters remain."
                                        + " Confirming the plan is NOT this tool.")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(),
                                        "required",
                                        List.of()))
                        .readOnly(false)
                        .concurrencySafe(false));
        this.turn = turn;
        this.articles = articles;
        this.articlePort = articlePort;
        this.chapters = chapters;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String callId = com.writingagent.v2.agent.support.ToolCallIdentity.require(param);
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> out = new LinkedHashMap<>();
                            out.put("tool", TOOL_NAME);
                            try {
                                List<String> outline = articles.confirmedChapterOutline(turn.projectId());
                                var article = articles.ensureArticle(turn.projectId());
                                List<Map<String, Object>> results = new ArrayList<>();
                                int missingBefore = 0;
                                for (int idx = 1; idx <= outline.size(); idx++) {
                                    var existing =
                                            articlePort.findCurrentChapter(article.articleId(), idx);
                                    boolean hasBody =
                                            existing.isPresent()
                                                    && existing.get().bodyMarkdown() != null
                                                    && !existing.get().bodyMarkdown().isBlank();
                                    if (hasBody) {
                                        continue;
                                    }
                                    missingBefore++;
                                    String perChapterCallId = callId + "#ch" + idx;
                                    String childId =
                                            MainAgentTurnContext.childClientActionId(
                                                    turn.parentRunId(), perChapterCallId, TOOL_NAME);
                                    Map<String, Object> one =
                                            chapters.generateOne(
                                                    GenerationCommand.chapter(
                                                            turn.projectId(),
                                                            turn.conversationId(),
                                                            childId,
                                                            idx,
                                                            "draft missing chapter " + idx,
                                                            MessagePolicy.mainAgentTool(),
                                                            turn.parentRunId()).withParentToolCallId(callId));
                                    Map<String, Object> slim = new LinkedHashMap<>();
                                    slim.put("chapterIndex", idx);
                                    slim.put("outcome", one.get("outcome"));
                                    slim.put("actionStatus", one.get("actionStatus"));
                                    slim.put("actionId", one.get("actionId"));
                                    slim.put("chapterRevisionId", one.get("chapterRevisionId"));
                                    slim.put("code", one.get("code"));
                                    slim.put("resultMayExist", one.get("resultMayExist"));
                                    results.add(slim);
                                }
                                out.put("missingBefore", missingBefore);
                                out.put("chapters", results);

                                // Authoritative completion: every outline chapter has CURRENT body,
                                // and no child op is FAILED / UNKNOWN / RUNNING-only replay.
                                boolean allBodies = true;
                                for (int idx = 1; idx <= outline.size(); idx++) {
                                    var existing =
                                            articlePort.findCurrentChapter(article.articleId(), idx);
                                    boolean hasBody =
                                            existing.isPresent()
                                                    && existing.get().bodyMarkdown() != null
                                                    && !existing.get().bodyMarkdown().isBlank();
                                    if (!hasBody) {
                                        allBodies = false;
                                        break;
                                    }
                                }
                                boolean opsOk =
                                        results.stream().allMatch(DraftConfirmedChaptersTool::isSuccessOp);
                                boolean complete = allBodies && opsOk;
                                out.put("complete", complete);
                                if (!complete) {
                                    out.put(
                                            "note",
                                            allBodies
                                                    ? "bodies present but child action not successful"
                                                    : "chapters still missing or failed");
                                } else if (missingBefore == 0) {
                                    out.put("note", "no missing chapters");
                                }
                            } catch (ArticleApplicationService.PlanNotConfirmedException e) {
                                out.put("code", "PLAN_NOT_CONFIRMED");
                                out.put("complete", false);
                            }
                            turn.recordReceipt(out);
                            String text = JSON.writeValueAsString(out);
                            return new ToolResultBlock(
                                    callId,
                                    TOOL_NAME,
                                    List.of(TextBlock.builder().text(text).build()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * REPLAY alone is not success — only RESULT_RECORDED (or fresh CHAPTER_SAVED /
     * CHAPTER_ALREADY_PRESENT) counts. FAILED / UNKNOWN / CLAIMED replays must keep complete=false.
     */
    static boolean isSuccessOp(Map<String, Object> r) {
        if (r == null) {
            return false;
        }
        String outcome = r.get("outcome") == null ? "" : String.valueOf(r.get("outcome"));
        String actionStatus =
                r.get("actionStatus") == null ? "" : String.valueOf(r.get("actionStatus"));
        if ("CHAPTER_SAVED".equals(outcome) || "CHAPTER_ALREADY_PRESENT".equals(outcome)) {
            return true;
        }
        if ("REPLAY".equals(outcome)
                && ActionStatus.RESULT_RECORDED.name().equals(actionStatus)
                && r.get("chapterRevisionId") != null) {
            return true;
        }
        return false;
    }
}

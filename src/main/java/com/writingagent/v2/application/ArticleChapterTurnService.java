package com.writingagent.v2.application;

import com.writingagent.v2.agent.chapter.ChapterAgentRuntime;
import com.writingagent.v2.agent.chapter.ReadConfirmedOutlineTool;
import com.writingagent.v2.agent.plan.ModelUnavailableException;
import com.writingagent.v2.agent.plan.PlanAgentRuntime;
import com.writingagent.v2.agent.plan.StructuredOutputMissingException;
import com.writingagent.v2.application.generation.MessagePolicy;
import com.writingagent.v2.application.port.ActionLedgerPort;
import com.writingagent.v2.application.port.ArticlePort;
import com.writingagent.v2.application.port.ConversationPort;
import com.writingagent.v2.domain.ActionStatus;
import com.writingagent.v2.domain.ArticleStatus;
import com.writingagent.v2.domain.ChapterOutcome;
import com.writingagent.v2.domain.RunStatus;
import com.writingagent.v2.domain.TaskStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Full-article chapter generation / rewrite turns (Harness ChapterAgent). */
@Service
public class ArticleChapterTurnService {

    public static final String OP_GENERATE_ALL = "GENERATE_ALL_CHAPTERS";
    public static final String OP_GENERATE_ONE = "GENERATE_CHAPTER";
    public static final String OP_REWRITE = "REWRITE_CHAPTER";

    private static final Logger log = LoggerFactory.getLogger(ArticleChapterTurnService.class);

    private final ProjectApplicationService projects;
    private final ActionLedgerService actions;
    private final TaskRunService taskRuns;
    private final ArticleApplicationService articles;
    private final ArticlePort articlePort;
    private final ChapterAgentRuntime chapterAgent;
    private final ConversationPort conversations;
    private final TransactionTemplate tx;

    public ArticleChapterTurnService(
            ProjectApplicationService projects,
            ActionLedgerService actions,
            TaskRunService taskRuns,
            ArticleApplicationService articles,
            ArticlePort articlePort,
            ChapterAgentRuntime chapterAgent,
            ConversationPort conversations,
            TransactionTemplate tx) {
        this.projects = projects;
        this.actions = actions;
        this.taskRuns = taskRuns;
        this.articles = articles;
        this.articlePort = articlePort;
        this.chapterAgent = chapterAgent;
        this.conversations = conversations;
        this.tx = tx;
    }

    public Map<String, Object> generateAllChapters(
            UUID projectId, UUID conversationId, String clientActionId, String message) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId required");
        }
        String digest =
                ActionLedgerService.digestFor(
                        projectId, conversationId, null, OP_GENERATE_ALL, message, null);
        var claim = actions.claim(projectId, clientActionId, digest, OP_GENERATE_ALL);
        if (claim.kind() == ActionLedgerService.ClaimKind.DIGEST_CONFLICT) {
            return error(409, "IDEMPOTENCY_KEY_REUSED", claim.actionId(), false, 0);
        }
        if (claim.kind() == ActionLedgerService.ClaimKind.IDEMPOTENT_REPLAY) {
            return replay(projectId, clientActionId, claim);
        }

        List<String> outline;
        ArticlePort.ArticleView article;
        try {
            outline = articles.confirmedChapterOutline(projectId);
            article = articles.ensureArticle(projectId);
        } catch (ArticleApplicationService.PlanNotConfirmedException e) {
            actions.markResult(
                    claim.actionId(), ActionStatus.FAILED, null, false, "PLAN_NOT_CONFIRMED");
            return error(409, "PLAN_NOT_CONFIRMED", claim.actionId(), false, 0);
        }

        tx.executeWithoutResult(
                s ->
                        conversations.insertUserMessage(
                                UUID.randomUUID(),
                                conversationId,
                                message == null || message.isBlank()
                                        ? "生成全部章节正文（按已确认大纲）"
                                        : message));

        UUID taskId =
                tx.execute(
                        status -> {
                            UUID t =
                                    taskRuns.createTask(
                                            projectId, conversationId, TaskStatus.RUNNING);
                            UUID runId =
                                    taskRuns.createRun(t, claim.actionId(), RunStatus.RUNNING);
                            actions.bindTaskRun(claim.actionId(), conversationId, t, runId);
                            taskRuns.appendEvent(runId, "RUN_STARTED", null, null, "generate_all_chapters");
                            return t;
                        });
        UUID runId = actions.find(projectId, clientActionId).orElseThrow().runId();

        int totalModels = 0;
        List<Map<String, Object>> generated = new ArrayList<>();
        List<Integer> failedIndexes = new ArrayList<>();

        // F1: each chapter uses an independent child action/source_action_id (V4 unique).
        for (int idx = 1; idx <= outline.size(); idx++) {
            var existing = articlePort.findCurrentChapter(article.articleId(), idx);
            if (existing.isPresent()
                    && existing.get().bodyMarkdown() != null
                    && !existing.get().bodyMarkdown().isBlank()) {
                Map<String, Object> skip = new LinkedHashMap<>();
                skip.put("chapterIndex", idx);
                skip.put("skipped", true);
                skip.put("status", "DONE");
                skip.put("outcome", "CHAPTER_ALREADY_PRESENT");
                skip.put("chapterRevisionId", existing.get().chapterRevisionId());
                skip.put("sourceActionId", existing.get().sourceActionId());
                generated.add(skip);
                continue;
            }

            String childClientActionId = "gall|" + claim.actionId() + "|ch=" + idx;
            taskRuns.appendEvent(
                    runId,
                    "CHAPTER_STARTED",
                    "ch-start-" + idx,
                    null,
                    "started idx=" + idx + "/" + outline.size() + " child=" + childClientActionId);

            Map<String, Object> one =
                    generateOneChapter(
                            projectId,
                            conversationId,
                            childClientActionId,
                            idx,
                            message == null || message.isBlank()
                                    ? "生成第" + idx + "章"
                                    : message,
                            MessagePolicy.mainAgentTool(),
                            runId);
            int models =
                    one.get("modelRequestCount") instanceof Number n
                            ? n.intValue()
                            : 0;
            totalModels += models;

            String outcome = one.get("outcome") == null ? "" : String.valueOf(one.get("outcome"));
            boolean ok =
                    "CHAPTER_SAVED".equals(outcome)
                            || "CHAPTER_ALREADY_PRESENT".equals(outcome)
                            || ("REPLAY".equals(outcome)
                                    && ActionStatus.RESULT_RECORDED
                                            .name()
                                            .equals(String.valueOf(one.get("actionStatus")))
                                    && one.get("chapterRevisionId") != null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chapterIndex", idx);
            row.put("outcome", outcome);
            row.put("actionStatus", one.get("actionStatus"));
            row.put("actionId", one.get("actionId"));
            row.put("chapterRevisionId", one.get("chapterRevisionId"));
            row.put("childClientActionId", childClientActionId);
            row.put("code", one.get("code"));
            row.put("modelRequestCount", models);
            generated.add(row);
            if (ok) {
                taskRuns.appendEvent(
                        runId, "CHAPTER_READY", "ch-" + idx, null, "chapter ready idx=" + idx);
            } else {
                failedIndexes.add(idx);
                taskRuns.appendEvent(
                        runId,
                        "CHAPTER_FAILED",
                        "ch-" + idx,
                        null,
                        "failed idx=" + idx + " outcome=" + outcome);
                break;
            }
        }

        articles.refreshArticleProgress(article.articleId());
        ArticlePort.ArticleView latest = articlePort.findById(article.articleId()).orElseThrow();
        boolean ready =
                ArticleStatus.READY_FOR_COMPLETE.name().equals(latest.status())
                        || ArticleStatus.COMPLETE.name().equals(latest.status());
        final boolean success = failedIndexes.isEmpty() && ready;
        final int modelTotal = totalModels;
        final List<Map<String, Object>> generatedSnapshot = List.copyOf(generated);
        final List<Integer> failedSnapshot = List.copyOf(failedIndexes);

        tx.executeWithoutResult(
                s -> {
                    taskRuns.requestRunSettlement(
                            runId, success ? RunStatus.SUCCEEDED : RunStatus.FAILED, modelTotal);
                    taskRuns.updateTaskStatus(
                            taskId, success ? TaskStatus.WAITING_USER : TaskStatus.FAILED);
                    actions.markResult(
                            claim.actionId(),
                            success ? ActionStatus.RESULT_RECORDED : ActionStatus.FAILED,
                            null,
                            true,
                            success ? null : "CHAPTERS_PARTIAL");
                    conversations.insertAssistantMessage(
                            UUID.randomUUID(),
                            conversationId,
                            chaptersBodyJson(
                                    success ? "CHAPTERS_READY" : "CHAPTERS_PARTIAL",
                                    claim.actionId(),
                                    latest.articleId(),
                                    latest.status(),
                                    generatedSnapshot,
                                    failedSnapshot,
                                    modelTotal), runId);
                });

        if (!success) {
            Map<String, Object> err =
                    error(502, "CHAPTERS_PARTIAL", claim.actionId(), true, modelTotal);
            err.put("articleId", latest.articleId());
            err.put("articleStatus", latest.status());
            err.put("chapters", generatedSnapshot);
            err.put("failedChapterIndexes", failedSnapshot);
            err.put("completedChapterCount", latest.completedChapterCount());
            err.put("chapterCount", latest.chapterCount());
            err.put("outcome", "CHAPTERS_PARTIAL");
            return err;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionId", claim.actionId());
        body.put("conversationId", conversationId);
        body.put("taskId", taskId);
        body.put("runId", runId);
        body.put("actionStatus", ActionStatus.RESULT_RECORDED.name());
        body.put("taskStatus", TaskStatus.WAITING_USER.name());
        body.put("runStatus", RunStatus.SUCCEEDED.name());
        body.put("outcome", "CHAPTERS_READY");
        body.put("articleId", latest.articleId());
        body.put("articleStatus", latest.status());
        body.put("chapterCount", latest.chapterCount());
        body.put("completedChapterCount", latest.completedChapterCount());
        body.put("chapters", generatedSnapshot);
        body.put("failedChapterIndexes", failedSnapshot);
        body.put("modelRequestCount", modelTotal);
        body.put("externalSourcesUsed", false);
        body.put(
                "evidenceDisclosure",
                "本次内容未检索外部来源；依据为用户目标与模型归纳（USER_GOAL_AND_MODEL_SYNTHESIS）。");
        body.put("httpStatusHint", 200);
        body.put("retryable", false);
        body.put("resultMayExist", true);
        return body;
    }

    public Map<String, Object> rewriteChapter(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            int chapterIndex,
            String message) {
        return rewriteChapter(
                projectId,
                conversationId,
                clientActionId,
                chapterIndex,
                message,
                MessagePolicy.workbenchButton());
    }

    public Map<String, Object> rewriteChapter(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            int chapterIndex,
            String message,
            MessagePolicy policy) {
        return rewriteChapter(
                projectId, conversationId, clientActionId, chapterIndex, message, policy, null);
    }

    public Map<String, Object> rewriteChapter(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            int chapterIndex,
            String message,
            MessagePolicy policy,
            UUID parentRunId) {
        return rewriteChapter(projectId, conversationId, clientActionId, chapterIndex, message, policy, parentRunId, null);
    }

    public Map<String, Object> rewriteChapter(UUID projectId, UUID conversationId, String clientActionId,
            int chapterIndex, String message, MessagePolicy policy, UUID parentRunId, String parentToolCallId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId required");
        }
        if (policy == null) {
            policy = MessagePolicy.workbenchButton();
        }
        String digest =
                ActionLedgerService.digestFor(
                        projectId,
                        conversationId,
                        null,
                        OP_REWRITE,
                        message + "|ch=" + chapterIndex,
                        null);
        var claim = actions.claim(projectId, clientActionId, digest, OP_REWRITE);
        if (claim.kind() == ActionLedgerService.ClaimKind.DIGEST_CONFLICT) {
            return error(409, "IDEMPOTENCY_KEY_REUSED", claim.actionId(), false, 0);
        }
        if (claim.kind() == ActionLedgerService.ClaimKind.IDEMPOTENT_REPLAY) {
            return replay(projectId, clientActionId, claim);
        }

        List<String> outline;
        try {
            outline = articles.confirmedChapterOutline(projectId);
        } catch (ArticleApplicationService.PlanNotConfirmedException rejected) {
            actions.markResult(claim.actionId(), ActionStatus.FAILED, null, false, "PLAN_NOT_CONFIRMED");
            return error(409, "PLAN_NOT_CONFIRMED", claim.actionId(), false, 0);
        }
        if (chapterIndex < 1 || chapterIndex > outline.size()) {
            actions.markResult(
                    claim.actionId(), ActionStatus.FAILED, null, false, "CHAPTER_INDEX_INVALID");
            return error(400, "CHAPTER_INDEX_INVALID", claim.actionId(), false, 0);
        }
        ArticlePort.ArticleView article = articles.ensureArticle(projectId);
        if (ArticleStatus.COMPLETE.name().equals(article.status())) {
            actions.markResult(
                    claim.actionId(), ActionStatus.FAILED, null, false, "ARTICLE_ALREADY_COMPLETE");
            return error(409, "ARTICLE_ALREADY_COMPLETE", claim.actionId(), false, 0);
        }

        if (policy.recordUserMessageAfterClaim()) {
            tx.executeWithoutResult(
                    s ->
                            conversations.insertUserMessage(
                                    UUID.randomUUID(),
                                    conversationId,
                                    message == null || message.isBlank()
                                            ? "重写第" + chapterIndex + "章"
                                            : message));
        }

        final UUID parent = parentRunId;
        UUID taskId =
                tx.execute(
                        status -> {
                            UUID t =
                                    taskRuns.createTask(
                                            projectId, conversationId, TaskStatus.RUNNING);
                            UUID runId =
                                    taskRuns.createRun(
                                            t, claim.actionId(), RunStatus.RUNNING, parent, parentToolCallId);
                            actions.bindTaskRun(claim.actionId(), conversationId, t, runId);
                            taskRuns.appendEvent(
                                    runId, "RUN_STARTED", null, null, "rewrite_chapter=" + chapterIndex);
                            return t;
                        });
        UUID runId = actions.find(projectId, clientActionId).orElseThrow().runId();
        String goal = projects.currentResearchGoal(projectId);
        List<ReadConfirmedOutlineTool.PriorChapter> prior =
                loadPrior(article.articleId(), chapterIndex);
        String existingBody =
                articlePort
                        .findCurrentChapter(article.articleId(), chapterIndex)
                        .map(ArticlePort.ChapterView::bodyMarkdown)
                        .orElse(null);
        String instruction =
                message == null || message.isBlank()
                        ? "请重写该章，保持与全文连贯，充实论证。"
                        : message.trim();
        final MessagePolicy msgPolicyRewrite = policy;

        try {
            ChapterAgentRuntime.AgentRunResult result =
                    chapterAgent.run(
                            projectId,
                            claim.actionId(),
                            article.articleId(),
                            chapterIndex,
                            outline.get(chapterIndex - 1),
                            goal,
                            outline,
                            prior,
                            instruction,
                            existingBody,
                            line -> persistChapterTrace(runId, claim.actionId(), chapterIndex, List.of(line)), runId);
            persistChapterTrace(runId, claim.actionId(), chapterIndex, result.trace());
            ChapterOutcome outcome = result.outcome();
            if (outcome == null
                    || outcome.bodyMarkdown == null
                    || outcome.bodyMarkdown.isBlank()
                    || outcome.chapterIndex != chapterIndex
                    || !result.ctxMark().equals(outcome.contextMarkEcho)) {
                return failRewrite(
                        conversationId,
                        claim.actionId(),
                        taskId,
                        runId,
                        "MODEL_OUTPUT_INVALID",
                        result.modelRequestCount(),
                        policy);
            }
            UUID chapterRevId =
                    tx.execute(
                            status -> {
                                taskRuns.guardBusinessCommit(runId);
                                UUID rev =
                                        articles.saveChapterDraft(
                                                article.articleId(),
                                                chapterIndex,
                                                outcome.title == null || outcome.title.isBlank()
                                                        ? outline.get(chapterIndex - 1)
                                                        : outcome.title,
                                                outcome.bodyMarkdown,
                                                ChapterOutcome.SOURCE_BASIS,
                                                claim.actionId());
                                articles.refreshArticleProgress(article.articleId());
                                if (injectChapterSettleFail()) {
                                    throw new IllegalStateException("INJECTED_CHAPTER_SETTLE_FAIL");
                                }
                                taskRuns.requestRunSettlement(
                                        runId, RunStatus.SUCCEEDED, result.modelRequestCount());
                                taskRuns.updateTaskStatus(taskId, TaskStatus.WAITING_USER);
                                actions.markResult(
                                        claim.actionId(),
                                        ActionStatus.RESULT_RECORDED,
                                        null,
                                        true,
                                        null);
                                if (msgPolicyRewrite.persistAssistantChatMessage()) {
                                    conversations.insertAssistantMessage(
                                            UUID.randomUUID(),
                                            conversationId,
                                            "{\"kind\":\"TURN_RESULT\",\"outcome\":\"CHAPTER_REWRITTEN\",\"actionId\":\""
                                                    + claim.actionId()
                                                    + "\",\"chapterIndex\":"
                                                    + chapterIndex
                                                    + ",\"chapterRevisionId\":\""
                                                    + rev
                                                    + "\",\"instructionBound\":true,\"modelRequestCount\":"
                                                    + result.modelRequestCount()
                                                    + "}", runId);
                                }
                                taskRuns.appendEvent(runId, "CHAPTER_REWRITTEN", null, rev.toString(),
                                        "saved idx=" + chapterIndex);
                                return rev;
                            });
            ArticlePort.ArticleView latest = articlePort.findById(article.articleId()).orElseThrow();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actionId", claim.actionId());
            body.put("outcome", "CHAPTER_REWRITTEN");
            body.put("articleId", latest.articleId());
            body.put("articleStatus", latest.status());
            body.put("chapterIndex", chapterIndex);
            body.put("chapterRevisionId", chapterRevId);
            body.put("instructionBound", true);
            body.put("userInstruction", instruction);
            body.put("bodyMarkdown", outcome.bodyMarkdown);
            body.put("modelRequestCount", result.modelRequestCount());
            body.put("httpStatusHint", 200);
            body.put("retryable", false);
            body.put("resultMayExist", true);
            body.put("invocationOrigin", policy.origin().name());
            return body;
        } catch (StructuredOutputMissingException ex) {
            persistPlanTrace(runId, claim.actionId(), ex.trace());
            return failRewrite(
                    conversationId,
                    claim.actionId(),
                    taskId,
                    runId,
                    WorkbenchTurnService.CODE_STRUCTURED_OUTPUT_MISSING,
                    ex.modelRequestCount(),
                    policy);
        } catch (ModelUnavailableException ex) {
            persistPlanTrace(runId, claim.actionId(), ex.trace());
            return failRewrite(
                    conversationId,
                    claim.actionId(),
                    taskId,
                    runId,
                    WorkbenchTurnService.CODE_MODEL_UNAVAILABLE,
                    ex.modelRequestCount(),
                    policy);
        }
    }

    /**
     * Generate exactly one chapter by index (page-driven progress). Skips if already non-empty
     * CURRENT body unless {@code force} is implied by missing body.
     */
    public Map<String, Object> generateOneChapter(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            int chapterIndex,
            String message) {
        return generateOneChapter(
                projectId,
                conversationId,
                clientActionId,
                chapterIndex,
                message,
                MessagePolicy.workbenchButton());
    }

    public Map<String, Object> generateOneChapter(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            int chapterIndex,
            String message,
            MessagePolicy policy) {
        return generateOneChapter(
                projectId,
                conversationId,
                clientActionId,
                chapterIndex,
                message,
                policy,
                null);
    }

    public Map<String, Object> generateOneChapter(
            UUID projectId,
            UUID conversationId,
            String clientActionId,
            int chapterIndex,
            String message,
            MessagePolicy policy,
            UUID parentRunId) {
        return generateOneChapter(projectId, conversationId, clientActionId, chapterIndex, message, policy, parentRunId, null);
    }

    public Map<String, Object> generateOneChapter(UUID projectId, UUID conversationId, String clientActionId,
            int chapterIndex, String message, MessagePolicy policy, UUID parentRunId, String parentToolCallId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId required");
        }
        if (policy == null) {
            policy = MessagePolicy.workbenchButton();
        }
        String digest =
                ActionLedgerService.digestFor(
                        projectId,
                        conversationId,
                        null,
                        OP_GENERATE_ONE,
                        "ch=" + chapterIndex,
                        null);
        var claim = actions.claim(projectId, clientActionId, digest, OP_GENERATE_ONE);
        if (claim.kind() == ActionLedgerService.ClaimKind.DIGEST_CONFLICT) {
            return error(409, "IDEMPOTENCY_KEY_REUSED", claim.actionId(), false, 0);
        }
        if (claim.kind() == ActionLedgerService.ClaimKind.IDEMPOTENT_REPLAY) {
            return replay(projectId, clientActionId, claim);
        }

        List<String> outline;
        ArticlePort.ArticleView article;
        try {
            outline = articles.confirmedChapterOutline(projectId);
            article = articles.ensureArticle(projectId);
        } catch (ArticleApplicationService.PlanNotConfirmedException e) {
            actions.markResult(
                    claim.actionId(), ActionStatus.FAILED, null, false, "PLAN_NOT_CONFIRMED");
            return error(409, "PLAN_NOT_CONFIRMED", claim.actionId(), false, 0);
        }
        if (chapterIndex < 1 || chapterIndex > outline.size()) {
            actions.markResult(
                    claim.actionId(), ActionStatus.FAILED, null, false, "CHAPTER_INDEX_INVALID");
            return error(400, "CHAPTER_INDEX_INVALID", claim.actionId(), false, 0);
        }
        if (ArticleStatus.COMPLETE.name().equals(article.status())) {
            actions.markResult(
                    claim.actionId(), ActionStatus.FAILED, null, false, "ARTICLE_ALREADY_COMPLETE");
            return error(409, "ARTICLE_ALREADY_COMPLETE", claim.actionId(), false, 0);
        }

        var existing = articlePort.findCurrentChapter(article.articleId(), chapterIndex);
        if (existing.isPresent()
                && existing.get().bodyMarkdown() != null
                && !existing.get().bodyMarkdown().isBlank()) {
            actions.markResult(
                    claim.actionId(), ActionStatus.RESULT_RECORDED, null, true, null);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actionId", claim.actionId());
            body.put("outcome", "CHAPTER_ALREADY_PRESENT");
            body.put("actionStatus", ActionStatus.RESULT_RECORDED.name());
            body.put("articleId", article.articleId());
            body.put("chapterIndex", chapterIndex);
            body.put("chapterRevisionId", existing.get().chapterRevisionId());
            body.put("skipped", true);
            body.put("status", "DONE");
            body.put("httpStatusHint", 200);
            body.put("retryable", false);
            body.put("resultMayExist", true);
            body.put("modelRequestCount", 0);
            return body;
        }

        if (policy.recordUserMessageAfterClaim()) {
            tx.executeWithoutResult(
                    s ->
                            conversations.insertUserMessage(
                                    UUID.randomUUID(),
                                    conversationId,
                                    message == null || message.isBlank()
                                            ? "生成第" + chapterIndex + "章"
                                            : message));
        }

        final UUID parent = parentRunId;
        UUID taskId =
                tx.execute(
                        status -> {
                            UUID t =
                                    taskRuns.createTask(
                                            projectId, conversationId, TaskStatus.RUNNING);
                            UUID runId =
                                    taskRuns.createRun(
                                            t, claim.actionId(), RunStatus.RUNNING, parent, parentToolCallId);
                            actions.bindTaskRun(claim.actionId(), conversationId, t, runId);
                            taskRuns.appendEvent(
                                    runId,
                                    "CHAPTER_STARTED",
                                    "ch-start-" + chapterIndex,
                                    null,
                                    "started idx=" + chapterIndex + "/" + outline.size());
                            return t;
                        });
        UUID runId = actions.find(projectId, clientActionId).orElseThrow().runId();
        String goal = projects.currentResearchGoal(projectId);
        List<ReadConfirmedOutlineTool.PriorChapter> prior =
                loadPrior(article.articleId(), chapterIndex);
        final MessagePolicy msgPolicyGen = policy;

        UUID chapterRevId = null;
        int modelRequestCount = 0;
        try {
            if (injectChapterModelFail()) {
                throw new ModelUnavailableException(
                        "INJECTED_CHAPTER_MODEL_FAIL", 0, List.of());
            }
            ChapterAgentRuntime.AgentRunResult result =
                    chapterAgent.run(
                            projectId,
                            claim.actionId(),
                            article.articleId(),
                            chapterIndex,
                            outline.get(chapterIndex - 1),
                            goal,
                            outline,
                            prior,
                            null,
                            null,
                            line -> persistChapterTrace(runId, claim.actionId(), chapterIndex, List.of(line)), runId);
            modelRequestCount = result.modelRequestCount();
            persistChapterTrace(runId, claim.actionId(), chapterIndex, result.trace());
            ChapterOutcome outcome = result.outcome();
            if (outcome == null
                    || !ChapterOutcome.CHAPTER_DRAFT_READY.equals(outcome.outcome)
                    || outcome.bodyMarkdown == null
                    || outcome.bodyMarkdown.isBlank()
                    || outcome.chapterIndex != chapterIndex
                    || !result.ctxMark().equals(outcome.contextMarkEcho)) {
                tx.executeWithoutResult(
                        s -> {
                            taskRuns.requestRunSettlement(
                                    runId, RunStatus.FAILED, result.modelRequestCount());
                            taskRuns.updateTaskStatus(taskId, TaskStatus.FAILED);
                            actions.markResult(
                                    claim.actionId(),
                                    ActionStatus.FAILED,
                                    null,
                                    false,
                                    "MODEL_OUTPUT_INVALID");
                            taskRuns.appendEvent(
                                    runId,
                                    "CHAPTER_INVALID",
                                    "ch-" + chapterIndex,
                                    null,
                                    "invalid idx=" + chapterIndex);
                        });
                return error(502, "MODEL_OUTPUT_INVALID", claim.actionId(), false, result.modelRequestCount());
            }
            chapterRevId =
                    tx.execute(
                            status -> {
                                taskRuns.guardBusinessCommit(runId);
                                UUID rev =
                                        articles.saveChapterDraft(
                                                article.articleId(),
                                                chapterIndex,
                                                outcome.title == null || outcome.title.isBlank()
                                                        ? outline.get(chapterIndex - 1)
                                                        : outcome.title,
                                                outcome.bodyMarkdown,
                                                ChapterOutcome.SOURCE_BASIS,
                                                claim.actionId());
                                articles.refreshArticleProgress(article.articleId());
                                if (injectChapterSettleFail()) {
                                    throw new IllegalStateException("INJECTED_CHAPTER_SETTLE_FAIL");
                                }
                                ArticlePort.ArticleView latest =
                                        articlePort.findById(article.articleId()).orElseThrow();
                                taskRuns.requestRunSettlement(
                                        runId, RunStatus.SUCCEEDED, result.modelRequestCount());
                                taskRuns.updateTaskStatus(taskId, TaskStatus.WAITING_USER);
                                actions.markResult(
                                        claim.actionId(),
                                        ActionStatus.RESULT_RECORDED,
                                        null,
                                        true,
                                        null);
                                taskRuns.appendEvent(
                                        runId,
                                        "CHAPTER_SAVED",
                                        "ch-" + chapterIndex,
                                        rev.toString(),
                                        "saved idx=" + chapterIndex);
                                if (msgPolicyGen.persistAssistantChatMessage()) {
                                    conversations.insertAssistantMessage(
                                            UUID.randomUUID(),
                                            conversationId,
                                            "{\"kind\":\"TURN_RESULT\",\"outcome\":\"CHAPTER_SAVED\",\"actionId\":\""
                                                    + claim.actionId()
                                                    + "\",\"chapterIndex\":"
                                                    + chapterIndex
                                                    + ",\"chapterRevisionId\":\""
                                                    + rev
                                                    + "\",\"articleStatus\":\""
                                                    + latest.status()
                                                    + "\",\"completedChapterCount\":"
                                                    + latest.completedChapterCount()
                                                    + ",\"chapterCount\":"
                                                    + latest.chapterCount()
                                                    + ",\"modelRequestCount\":"
                                                    + result.modelRequestCount()
                                                    + "}", runId);
                                }
                                return rev;
                            });
            ArticlePort.ArticleView latest = articlePort.findById(article.articleId()).orElseThrow();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actionId", claim.actionId());
            body.put("outcome", "CHAPTER_SAVED");
            body.put("actionStatus", ActionStatus.RESULT_RECORDED.name());
            body.put("articleId", latest.articleId());
            body.put("articleStatus", latest.status());
            body.put("chapterIndex", chapterIndex);
            body.put("chapterRevisionId", chapterRevId);
            body.put("title", outcome.title);
            body.put("status", "DONE");
            body.put("completedChapterCount", latest.completedChapterCount());
            body.put("chapterCount", latest.chapterCount());
            body.put("modelRequestCount", result.modelRequestCount());
            body.put("httpStatusHint", 200);
            body.put("retryable", false);
            body.put("resultMayExist", true);
            body.put("invocationOrigin", policy.origin().name());
            return body;
        } catch (StructuredOutputMissingException ex) {
            persistPlanTrace(runId, claim.actionId(), ex.trace());
            settleChapterFailure(
                    claim.actionId(),
                    taskId,
                    runId,
                    conversationId,
                    chapterIndex,
                    WorkbenchTurnService.CODE_STRUCTURED_OUTPUT_MISSING,
                    ex.modelRequestCount(),
                    false,
                    "CHAPTER_SO_MISSING");
            Map<String, Object> err =
                    error(
                            502,
                            WorkbenchTurnService.CODE_STRUCTURED_OUTPUT_MISSING,
                            claim.actionId(),
                            false,
                            ex.modelRequestCount());
            err.put("chapterIndex", chapterIndex);
            err.put("status", "FAILED");
            return err;
        } catch (ModelUnavailableException ex) {
            persistPlanTrace(runId, claim.actionId(), ex.trace());
            settleChapterFailure(
                    claim.actionId(),
                    taskId,
                    runId,
                    conversationId,
                    chapterIndex,
                    WorkbenchTurnService.CODE_MODEL_UNAVAILABLE,
                    ex.modelRequestCount(),
                    false,
                    "CHAPTER_MODEL_UNAVAILABLE");
            Map<String, Object> err =
                    error(
                            503,
                            WorkbenchTurnService.CODE_MODEL_UNAVAILABLE,
                            claim.actionId(),
                            false,
                            ex.modelRequestCount());
            err.put("chapterIndex", chapterIndex);
            err.put("status", "FAILED");
            return err;
        } catch (Exception ex) {
            // After R1 atomic save+settle: rollback means this action did not persist a chapter.
            // Do not treat unrelated existing CURRENT body as "this action wrote".
            final boolean bodyExists =
                    articlePort
                            .findCurrentChapter(article.articleId(), chapterIndex)
                            .filter(
                                    c ->
                                            claim.actionId().equals(c.sourceActionId())
                                                    && c.bodyMarkdown() != null
                                                    && !c.bodyMarkdown().isBlank())
                            .isPresent();
            // If chapter body may already be durable for THIS action, never claim "no result".
            final String code = bodyExists ? "UNKNOWN_GENERATION" : "CHAPTER_UNKNOWN";
            final ActionStatus status =
                    bodyExists ? ActionStatus.UNKNOWN_GENERATION : ActionStatus.FAILED;
            final int modelsFinal = modelRequestCount;
            final UUID revFinal =
                    articlePort
                            .findCurrentChapter(article.articleId(), chapterIndex)
                            .filter(c -> claim.actionId().equals(c.sourceActionId()))
                            .map(ArticlePort.ChapterView::chapterRevisionId)
                            .orElse(null);
            final String exSummary =
                    ex.getClass().getSimpleName()
                            + ": "
                            + (ex.getMessage() == null
                                    ? ""
                                    : ex.getMessage()
                                            .substring(0, Math.min(200, ex.getMessage().length())));
            final MessagePolicy msgPolicy = policy;
            tx.executeWithoutResult(
                    s -> {
                        taskRuns.requestRunSettlement(
                                runId,
                                bodyExists ? RunStatus.UNKNOWN : RunStatus.FAILED,
                                modelsFinal);
                        taskRuns.updateTaskStatus(taskId, TaskStatus.FAILED);
                        actions.markResult(claim.actionId(), status, null, bodyExists, code);
                        taskRuns.appendEvent(
                                runId,
                                code,
                                "ch-" + chapterIndex,
                                revFinal == null ? null : revFinal.toString(),
                                exSummary);
                        if (msgPolicy.persistAssistantChatMessage()) {
                            conversations.insertAssistantMessage(
                                    UUID.randomUUID(),
                                    conversationId,
                                    "{\"kind\":\"TURN_FAILED\",\"code\":\""
                                            + code
                                            + "\",\"actionId\":\""
                                            + claim.actionId()
                                            + "\",\"chapterIndex\":"
                                            + chapterIndex
                                            + ",\"resultMayExist\":"
                                            + bodyExists
                                            + ",\"modelRequestCount\":"
                                            + modelsFinal
                                            + ",\"summary\":\""
                                            + (bodyExists
                                                    ? "chapter body may exist; do not blind regenerate"
                                                    : "no durable chapter body observed")
                                            + "\"}", runId);
                        }
                    });
            Map<String, Object> err =
                    error(500, code, claim.actionId(), bodyExists, modelsFinal);
            err.put("chapterIndex", chapterIndex);
            err.put("status", status.name());
            err.put("actionStatus", status.name());
            if (revFinal != null) {
                err.put("chapterRevisionId", revFinal);
            }
            err.put("message", ex.getClass().getSimpleName());
            return err;
        }
    }

    private void settleChapterFailure(
            UUID actionId,
            UUID taskId,
            UUID runId,
            UUID conversationId,
            int chapterIndex,
            String code,
            int modelRequestCount,
            boolean resultMayExist,
            String eventKind) {
        tx.executeWithoutResult(
                s -> {
                    taskRuns.requestRunSettlement(runId, RunStatus.FAILED, modelRequestCount);
                    taskRuns.updateTaskStatus(taskId, TaskStatus.FAILED);
                    actions.markResult(actionId, ActionStatus.FAILED, null, resultMayExist, code);
                    taskRuns.appendEvent(
                            runId, eventKind, "ch-" + chapterIndex, null, "idx=" + chapterIndex);
                });
    }

    static boolean injectChapterModelFail() {
        return "1".equals(System.getenv("WA2_INJECT_CHAPTER_MODEL_FAIL"))
                || Boolean.getBoolean("wa2.inject.chapter.model.fail");
    }

    static boolean injectChapterSettleFail() {
        return "1".equals(System.getenv("WA2_INJECT_CHAPTER_SETTLE_FAIL"))
                || Boolean.getBoolean("wa2.inject.chapter.settle.fail");
    }

    private Map<String, Object> failRewrite(
            UUID conversationId,
            UUID actionId,
            UUID taskId,
            UUID runId,
            String code,
            int modelRequestCount,
            MessagePolicy policy) {
        tx.executeWithoutResult(
                s -> {
                    taskRuns.requestRunSettlement(runId, RunStatus.FAILED, modelRequestCount);
                    taskRuns.updateTaskStatus(taskId, TaskStatus.FAILED);
                    actions.markResult(actionId, ActionStatus.FAILED, null, false, code);
                    if (policy == null || policy.persistAssistantChatMessage()) {
                        conversations.insertAssistantMessage(
                                UUID.randomUUID(),
                                conversationId,
                                "{\"kind\":\"TURN_FAILED\",\"code\":\""
                                        + code
                                        + "\",\"actionId\":\""
                                        + actionId
                                        + "\",\"modelRequestCount\":"
                                        + modelRequestCount
                                        + "}", runId);
                    }
                });
        int http =
                WorkbenchTurnService.CODE_STRUCTURED_OUTPUT_MISSING.equals(code)
                        ? 502
                        : WorkbenchTurnService.CODE_MODEL_UNAVAILABLE.equals(code) ? 503 : 502;
        return error(http, code, actionId, false, modelRequestCount);
    }

    private List<ReadConfirmedOutlineTool.PriorChapter> loadPrior(UUID articleId, int beforeIndex) {
        List<ReadConfirmedOutlineTool.PriorChapter> prior = new ArrayList<>();
        for (ArticlePort.ChapterView ch : articlePort.listCurrentChapters(articleId)) {
            if (ch.chapterIndex() >= beforeIndex) {
                continue;
            }
            String body = ch.bodyMarkdown() == null ? "" : ch.bodyMarkdown();
            String snip = body.length() > 400 ? body.substring(0, 400) + "…" : body;
            prior.add(
                    new ReadConfirmedOutlineTool.PriorChapter(
                            ch.chapterIndex(), ch.title(), snip));
        }
        return prior;
    }

    private void persistChapterTrace(
            UUID runId, UUID actionId, int chapterIndex, List<ChapterAgentRuntime.TraceLine> trace) {
        if (trace == null) {
            return;
        }
        for (var line : trace) {
            String tc = "ch" + chapterIndex + "-" + line.kind() + "-" + line.seq();
            tx.executeWithoutResult(
                    s ->
                            taskRuns.appendTrace(
                                    runId,
                                    "stage-trace-" + line.seq(),
                                    line.kind(),
                                    tc,
                                    line.detail() == null ? "" : line.detail()));
        }
    }

    private void persistPlanTrace(
            UUID runId, UUID actionId, List<PlanAgentRuntime.TraceLine> trace) {
        if (trace == null) {
            return;
        }
        for (var line : trace) {
            String tc = "so-" + actionId + "-" + line.seq();
            tx.executeWithoutResult(
                    s ->
                            taskRuns.appendTrace(
                                    runId,
                                    "stage-trace-" + line.seq(),
                                    line.kind(),
                                    tc,
                                    line.detail() == null ? "" : line.detail()));
        }
    }

    private Map<String, Object> replay(
            UUID projectId, String clientActionId, ActionLedgerService.ClaimResult claim) {
        ActionLedgerPort.ActionLedgerView row =
                actions.find(projectId, clientActionId).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionId", claim.actionId());
        body.put("conversationId", row.conversationId());
        body.put("taskId", row.taskId());
        body.put("runId", row.runId());
        body.put("actionStatus", row.status());
        body.put("retryable", false);
        body.put("resultMayExist", row.resultMayExist());
        body.put("modelRequestCount", 0);
        if (row.errorCode() != null) {
            body.put("code", row.errorCode());
        }
        articlePort
                .findBySourceActionId(claim.actionId())
                .ifPresent(
                        ch -> {
                            body.put("chapterRevisionId", ch.chapterRevisionId());
                            body.put("chapterIndex", ch.chapterIndex());
                            body.put("reconciledBy", "source_action_id");
                        });

        String status = row.status();
        if (ActionStatus.CLAIMED.name().equals(status)) {
            body.put("outcome", "RUNNING");
            body.put("httpStatusHint", 202);
            return body;
        }
        if (ActionStatus.FAILED.name().equals(status)) {
            body.put("outcome", "REPLAY_FAILED");
            body.put("httpStatusHint", 200);
            return body;
        }
        if (ActionStatus.UNKNOWN_GENERATION.name().equals(status)) {
            body.put("outcome", "REPLAY_UNKNOWN");
            body.put("httpStatusHint", 200);
            return body;
        }
        // RESULT_RECORDED (and any other terminal success)
        body.put("outcome", "REPLAY");
        body.put("httpStatusHint", 200);
        return body;
    }

    private static Map<String, Object> error(
            int http, String code, UUID actionId, boolean resultMayExist, int modelRequestCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", code);
        body.put("retryable", false);
        body.put("actionId", actionId);
        body.put("resultMayExist", resultMayExist);
        body.put("httpStatusHint", http);
        body.put("modelRequestCount", modelRequestCount);
        return body;
    }

    private static String chaptersBodyJson(
            String outcome,
            UUID actionId,
            UUID articleId,
            String articleStatus,
            List<Map<String, Object>> generated,
            List<Integer> failed,
            int modelRequestCount) {
        return "{\"kind\":\"TURN_RESULT\",\"outcome\":\""
                + outcome
                + "\",\"actionId\":\""
                + actionId
                + "\",\"articleId\":\""
                + articleId
                + "\",\"articleStatus\":\""
                + articleStatus
                + "\",\"generatedCount\":"
                + generated.size()
                + ",\"failedChapterIndexes\":"
                + failed
                + ",\"modelRequestCount\":"
                + modelRequestCount
                + "}";
    }
}

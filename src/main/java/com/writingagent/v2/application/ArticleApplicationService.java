package com.writingagent.v2.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.port.ArticlePort;
import com.writingagent.v2.application.port.PlanRevisionPort;
import com.writingagent.v2.application.port.ProjectPort;
import com.writingagent.v2.application.support.RequestDigests;
import com.writingagent.v2.domain.ArticleStatus;
import com.writingagent.v2.domain.ChapterOutcome;
import com.writingagent.v2.domain.PlanOutcome;
import com.writingagent.v2.domain.PlanRevisionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Article/chapter persistence authority. Agent never writes DB; Application validates completeness.
 */
@Service
public class ArticleApplicationService {

    public static class IncompleteArticleException extends RuntimeException {
        public IncompleteArticleException(String msg) {
            super(msg);
        }
    }

    public static class PlanNotConfirmedException extends RuntimeException {
        public PlanNotConfirmedException(String msg) {
            super(msg);
        }
    }

    private final ArticlePort articles;
    private final PlanRevisionPort plans;
    private final ProjectPort projects;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public ArticleApplicationService(
            ArticlePort articles, PlanRevisionPort plans, ProjectPort projects) {
        this.articles = articles;
        this.plans = plans;
        this.projects = projects;
    }

    public List<String> confirmedChapterOutline(UUID projectId) {
        UUID planId = projects.currentPlanRevisionId(projectId);
        if (planId == null) {
            throw new PlanNotConfirmedException("no current plan");
        }
        PlanRevisionPort.PlanRevisionView plan = plans.findById(planId);
        if (!PlanRevisionStatus.CONFIRMED.name().equals(plan.status())) {
            throw new PlanNotConfirmedException("plan not CONFIRMED");
        }
        return extractOutline(plan.payloadJson());
    }

    @Transactional
    public ArticlePort.ArticleView ensureArticle(UUID projectId) {
        Optional<ArticlePort.ArticleView> existing = articles.findByProjectId(projectId);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID planId = projects.currentPlanRevisionId(projectId);
        if (planId == null) {
            throw new PlanNotConfirmedException("no current plan");
        }
        PlanRevisionPort.PlanRevisionView plan = plans.findById(planId);
        if (!PlanRevisionStatus.CONFIRMED.name().equals(plan.status())) {
            throw new PlanNotConfirmedException("plan not CONFIRMED");
        }
        List<String> outline = extractOutline(plan.payloadJson());
        if (outline.isEmpty()) {
            throw new IllegalStateException("confirmed plan has empty chapterOutline");
        }
        ProjectPort.ProjectView project = projects.getProject(projectId);
        UUID articleId = UUID.randomUUID();
        articles.insertArticle(
                articleId,
                projectId,
                planId,
                ArticleStatus.DRAFTING.name(),
                project.title(),
                outline.size(),
                false,
                PlanOutcome.SOURCE_BASIS);
        articles.setProjectCurrentArticle(projectId, articleId);
        return articles.findById(articleId).orElseThrow();
    }

    @Transactional
    public UUID saveChapterDraft(
            UUID articleId, int chapterIndex, String title, String bodyMarkdown, String sourceBasis) {
        return saveChapterDraft(articleId, chapterIndex, title, bodyMarkdown, sourceBasis, null);
    }

    @Transactional
    public UUID saveChapterDraft(
            UUID articleId,
            int chapterIndex,
            String title,
            String bodyMarkdown,
            String sourceBasis,
            UUID sourceActionId) {
        if (bodyMarkdown == null || bodyMarkdown.isBlank()) {
            throw new IllegalArgumentException("chapter body empty");
        }
        articles.lockArticleForUpdate(articleId);
        holdArticleLockIfInjected();
        ArticlePort.ArticleView article = articles.findById(articleId).orElseThrow();
        if (chapterIndex < 1 || chapterIndex > article.chapterCount()) {
            throw new IllegalArgumentException("chapterIndex out of outline range");
        }
        if (ArticleStatus.COMPLETE.name().equals(article.status())) {
            throw new IllegalStateException("article already COMPLETE");
        }
        articles.supersedeCurrentChapter(articleId, chapterIndex);
        long rev = articles.nextChapterRevisionNo(articleId, chapterIndex);
        UUID chapterRevisionId = UUID.randomUUID();
        String hash = RequestDigests.contentHash(bodyMarkdown);
        articles.insertChapterCurrent(
                chapterRevisionId,
                articleId,
                chapterIndex,
                rev,
                title == null || title.isBlank() ? "第" + chapterIndex + "章" : title,
                bodyMarkdown,
                hash,
                sourceBasis == null ? ChapterOutcome.SOURCE_BASIS : sourceBasis,
                sourceActionId);
        refreshArticleProgress(articleId);
        return chapterRevisionId;
    }

    @Transactional
    public Map<String, Object> completeArticle(UUID projectId, UUID articleId) {
        articles.lockArticleForUpdate(articleId);
        holdArticleLockIfInjected();
        ArticlePort.ArticleView article =
                articles.findById(articleId).orElseThrow(() -> new IllegalArgumentException("ARTICLE_NOT_FOUND"));
        if (!article.projectId().equals(projectId)) {
            throw new IllegalArgumentException("ARTICLE_PROJECT_MISMATCH");
        }
        if (ArticleStatus.COMPLETE.name().equals(article.status())) {
            return Map.of(
                    "articleId",
                    articleId,
                    "status",
                    ArticleStatus.COMPLETE.name(),
                    "alreadyComplete",
                    true);
        }
        List<ArticlePort.ChapterView> chapters = articles.listCurrentChapters(articleId);
        List<Integer> missing = missingChapterIndexes(article.chapterCount(), chapters);
        if (!missing.isEmpty()) {
            throw new IncompleteArticleException("missing chapters: " + missing);
        }
        for (ArticlePort.ChapterView ch : chapters) {
            if (ch.bodyMarkdown() == null || ch.bodyMarkdown().isBlank()) {
                throw new IncompleteArticleException("empty body chapterIndex=" + ch.chapterIndex());
            }
        }
        articles.updateArticleStatus(
                articleId, ArticleStatus.COMPLETE.name(), article.chapterCount(), Instant.now());
        return Map.of(
                "articleId",
                articleId,
                "status",
                ArticleStatus.COMPLETE.name(),
                "chapterCount",
                article.chapterCount(),
                "completedChapterCount",
                article.chapterCount());
    }

    public void refreshArticleProgress(UUID articleId) {
        ArticlePort.ArticleView article = articles.findById(articleId).orElseThrow();
        if (ArticleStatus.COMPLETE.name().equals(article.status())) {
            return;
        }
        List<ArticlePort.ChapterView> chapters = articles.listCurrentChapters(articleId);
        int done = 0;
        for (ArticlePort.ChapterView ch : chapters) {
            if (ch.bodyMarkdown() != null && !ch.bodyMarkdown().isBlank()) {
                done++;
            }
        }
        String status;
        if (done <= 0) {
            status = ArticleStatus.DRAFTING.name();
        } else if (done < article.chapterCount()) {
            status = ArticleStatus.PARTIAL.name();
        } else {
            status = ArticleStatus.READY_FOR_COMPLETE.name();
        }
        articles.updateArticleStatus(articleId, status, done, null);
    }

    public Map<String, Object> articleProjection(UUID projectId) {
        Optional<ArticlePort.ArticleView> opt = articles.findByProjectId(projectId);
        Map<String, Object> body = new LinkedHashMap<>();
        List<String> outline = List.of();
        try {
            UUID planId = projects.currentPlanRevisionId(projectId);
            if (planId != null) {
                PlanRevisionPort.PlanRevisionView plan = plans.findById(planId);
                if (PlanRevisionStatus.CONFIRMED.name().equals(plan.status())) {
                    outline = extractOutline(plan.payloadJson());
                }
            }
        } catch (Exception ignored) {
            // outline optional for projection
        }
        body.put("confirmedOutline", outline);

        if (opt.isEmpty()) {
            body.put("article", null);
            body.put("chapters", List.of());
            body.put("chapterProgress", progressRows(outline, List.of(), null));
            body.put("externalSourcesUsed", false);
            body.put(
                    "evidenceDisclosure",
                    "本次内容未检索外部来源；依据为用户目标与模型归纳（USER_GOAL_AND_MODEL_SYNTHESIS）。");
            return body;
        }
        ArticlePort.ArticleView a = opt.get();
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("articleId", a.articleId());
        article.put("planRevisionId", a.planRevisionId());
        article.put("status", a.status());
        article.put("title", a.title());
        article.put("chapterCount", a.chapterCount());
        article.put("completedChapterCount", a.completedChapterCount());
        article.put("externalSourcesUsed", a.externalSourcesUsed());
        article.put("evidenceBasis", a.evidenceBasis());
        article.put("completedAt", a.completedAt() == null ? null : a.completedAt().toString());
        body.put("article", article);

        List<ArticlePort.ChapterView> current = articles.listCurrentChapters(a.articleId());
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (ArticlePort.ChapterView ch : current) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chapterRevisionId", ch.chapterRevisionId());
            row.put("chapterIndex", ch.chapterIndex());
            row.put("revisionNo", ch.revisionNo());
            row.put("title", ch.title());
            row.put("bodyMarkdown", ch.bodyMarkdown());
            row.put("contentHash", ch.contentHash());
            row.put("sourceBasis", ch.sourceBasis());
            chapters.add(row);
        }
        body.put("chapters", chapters);
        body.put("chapterProgress", progressRows(outline, current, null));
        body.put("externalSourcesUsed", a.externalSourcesUsed());
        body.put(
                "evidenceDisclosure",
                a.externalSourcesUsed()
                        ? "本次内容声称使用了外部来源（MVP 未接入检索；请人工核验）。"
                        : "本次内容未检索外部来源；依据为用户目标与模型归纳（USER_GOAL_AND_MODEL_SYNTHESIS）。");
        return body;
    }

    static List<Map<String, Object>> progressRows(
            List<String> outline,
            List<ArticlePort.ChapterView> current,
            Integer runningIndex) {
        Map<Integer, ArticlePort.ChapterView> byIdx = new HashMap<>();
        for (ArticlePort.ChapterView ch : current) {
            byIdx.put(ch.chapterIndex(), ch);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < outline.size(); i++) {
            int idx = i + 1;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chapterIndex", idx);
            row.put("title", outline.get(i));
            ArticlePort.ChapterView ch = byIdx.get(idx);
            String status;
            if (runningIndex != null && runningIndex == idx) {
                status = "RUNNING";
            } else if (ch != null && ch.bodyMarkdown() != null && !ch.bodyMarkdown().isBlank()) {
                status = "DONE";
            } else {
                status = "PENDING";
            }
            row.put("status", status);
            row.put(
                    "hasBody",
                    ch != null && ch.bodyMarkdown() != null && !ch.bodyMarkdown().isBlank());
            rows.add(row);
        }
        return rows;
    }

    public static List<Integer> missingChapterIndexes(int chapterCount, List<ArticlePort.ChapterView> chapters) {
        boolean[] present = new boolean[chapterCount + 1];
        for (ArticlePort.ChapterView ch : chapters) {
            if (ch.chapterIndex() >= 1
                    && ch.chapterIndex() <= chapterCount
                    && ch.bodyMarkdown() != null
                    && !ch.bodyMarkdown().isBlank()) {
                present[ch.chapterIndex()] = true;
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= chapterCount; i++) {
            if (!present[i]) {
                missing.add(i);
            }
        }
        return missing;
    }

    /** Test inject: hold FOR UPDATE longer so concurrent peers can queue on the same article row. */
    static void holdArticleLockIfInjected() {
        String env = System.getenv("WA2_INJECT_ARTICLE_LOCK_HOLD_MS");
        if (env == null || env.isBlank()) {
            return;
        }
        try {
            long ms = Long.parseLong(env.trim());
            if (ms > 0) {
                Thread.sleep(ms);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (NumberFormatException ignored) {
            // no-op
        }
    }

    List<String> extractOutline(String payloadJson) {
        try {
            JsonNode root = json.readTree(payloadJson);
            JsonNode outline = root.path("plan").path("chapterOutline");
            if (!outline.isArray()) {
                outline = root.path("chapterOutline");
            }
            List<String> titles = new ArrayList<>();
            if (outline.isArray()) {
                for (JsonNode n : outline) {
                    String t = n.asText("").trim();
                    if (!t.isEmpty()) {
                        titles.add(t);
                    }
                }
            }
            return titles;
        } catch (Exception e) {
            throw new IllegalStateException("invalid plan payload for outline", e);
        }
    }
}

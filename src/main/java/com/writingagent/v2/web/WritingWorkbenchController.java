package com.writingagent.v2.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.ActionLedgerService;
import com.writingagent.v2.application.ArticleApplicationService;
import com.writingagent.v2.application.ArticleChapterTurnService;
import com.writingagent.v2.application.MainAgentTurnUseCase;
import com.writingagent.v2.application.PlanEditConfirmUseCase;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.WorkbenchTurnService;
import com.writingagent.v2.application.port.ActionLedgerPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/writing/v2")
public class WritingWorkbenchController {

    private static final Pattern CHAPTER_ACTION_ID =
            Pattern.compile("^gch-(\\d+)-");

    private final ProjectApplicationService projects;
    private final WorkbenchTurnService turns;
    private final PlanEditConfirmUseCase editConfirm;
    private final ArticleChapterTurnService chapterTurns;
    private final ArticleApplicationService articles;
    private final ActionLedgerService actions;
    private final MainAgentTurnUseCase mainAgentTurns;
    private final com.writingagent.v2.application.capability.WritingCapability writing;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public WritingWorkbenchController(
            ProjectApplicationService projects,
            WorkbenchTurnService turns,
            PlanEditConfirmUseCase editConfirm,
            ArticleChapterTurnService chapterTurns,
            ArticleApplicationService articles,
            ActionLedgerService actions,
            MainAgentTurnUseCase mainAgentTurns,
            com.writingagent.v2.application.capability.WritingCapability writing) {
        this.projects = projects;
        this.turns = turns;
        this.editConfirm = editConfirm;
        this.chapterTurns = chapterTurns;
        this.articles = articles;
        this.actions = actions;
        this.mainAgentTurns = mainAgentTurns;
        this.writing = writing;
    }

    @PostMapping("/workspaces")
    public Map<String, Object> createWorkspace(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("displayName", "local"));
        return projects.createWorkspace(name, "local-fixture");
    }

    @PostMapping("/projects")
    public Map<String, Object> createProject(@RequestBody Map<String, Object> body) {
        UUID workspaceId = UUID.fromString(String.valueOf(body.get("workspaceId")));
        writing.requireWorkspace(workspaceId);
        String title = String.valueOf(body.get("title"));
        String goal = String.valueOf(body.get("researchGoal"));
        return projects.createProject(workspaceId, title, goal);
    }

    @PostMapping("/projects/{projectId}/turns")
    public ResponseEntity<Map<String, Object>> turn(
            @PathVariable("projectId") UUID projectId, @RequestBody Map<String, Object> body) {
        UUID conversationId =
                body.get("conversationId") == null
                        ? null
                        : UUID.fromString(String.valueOf(body.get("conversationId")));
        String clientActionId = String.valueOf(body.get("clientActionId"));
        String message = String.valueOf(body.getOrDefault("message", ""));
        String operation = String.valueOf(body.getOrDefault("operation", WorkbenchTurnService.OP));

        Map<String, Object> result;
        if (MainAgentTurnUseCase.OP.equals(operation)) {
            result = mainAgentTurns.runTurn(projectId, conversationId, clientActionId, message);
        } else if (WorkbenchTurnService.OP.equals(operation)) {
            result = turns.generateOrContinue(projectId, conversationId, clientActionId, message);
        } else if (ArticleChapterTurnService.OP_GENERATE_ALL.equals(operation)) {
            result =
                    chapterTurns.generateAllChapters(
                            projectId, conversationId, clientActionId, message);
        } else if (ArticleChapterTurnService.OP_GENERATE_ONE.equals(operation)) {
            int chapterIndex = ((Number) body.getOrDefault("chapterIndex", 0)).intValue();
            result =
                    chapterTurns.generateOneChapter(
                            projectId, conversationId, clientActionId, chapterIndex, message);
        } else if (ArticleChapterTurnService.OP_REWRITE.equals(operation)) {
            int chapterIndex = ((Number) body.getOrDefault("chapterIndex", 0)).intValue();
            result =
                    chapterTurns.rewriteChapter(
                            projectId, conversationId, clientActionId, chapterIndex, message);
        } else {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INVALID_REQUEST", "message", "operation not supported"));
        }
        int http = ((Number) result.getOrDefault("httpStatusHint", 200)).intValue();
        return ResponseEntity.status(http).body(result);
    }

    @GetMapping("/projects/{projectId}/workbench")
    public Map<String, Object> workbench(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(value="conversationId",required=false) UUID conversationId) {
        return writing.workbench(projectId);
    }

    @SuppressWarnings("unchecked")
    private void mergeChapterActionProgress(Map<String, Object> wb, UUID projectId) {
        List<Map<String, Object>> progress =
                wb.get("chapterProgress") instanceof List<?> list
                        ? (List<Map<String, Object>>) list
                        : new ArrayList<>();
        List<Map<String, Object>> inflight = new ArrayList<>();
        // First CLAIMED / FAILED per chapterIndex wins (list ordered by updated_at DESC).
        Map<Integer, ActionLedgerPort.ChapterActionRow> latestByChapter = new LinkedHashMap<>();
        for (ActionLedgerPort.ChapterActionRow row : actions.listChapterActions(projectId)) {
            Matcher m = CHAPTER_ACTION_ID.matcher(row.clientActionId() == null ? "" : row.clientActionId());
            if (!m.find()) {
                continue;
            }
            int idx = Integer.parseInt(m.group(1));
            latestByChapter.putIfAbsent(idx, row);
        }
        for (Map.Entry<Integer, ActionLedgerPort.ChapterActionRow> e : latestByChapter.entrySet()) {
            int idx = e.getKey();
            ActionLedgerPort.ChapterActionRow row = e.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("chapterIndex", idx);
            info.put("clientActionId", row.clientActionId());
            info.put("actionId", row.actionId());
            info.put("operation", row.operation());
            info.put("status", row.status());
            info.put("errorCode", row.errorCode());
            info.put("runId", row.runId());
            inflight.add(info);

            for (Map<String, Object> p : progress) {
                if (((Number) p.get("chapterIndex")).intValue() != idx) {
                    continue;
                }
                // Never override DONE (has body) with FAILED/CLAIMED from older attempts.
                if ("DONE".equals(String.valueOf(p.get("status")))) {
                    continue;
                }
                p.put("clientActionId", row.clientActionId());
                p.put("actionId", row.actionId());
                p.put("runId", row.runId());
                if ("CLAIMED".equals(row.status())) {
                    p.put("status", "RUNNING");
                    p.put("serverActionStatus", "CLAIMED");
                } else if ("FAILED".equals(row.status())) {
                    p.put("status", "FAILED");
                    p.put("serverActionStatus", "FAILED");
                    p.put("errorCode", row.errorCode());
                }
            }
        }
        wb.put("chapterActions", inflight);
        wb.put("chapterProgress", progress);
    }

    @PostMapping("/projects/{projectId}/plans/{planRevisionId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("planRevisionId") UUID planRevisionId,
            @RequestBody Map<String, Object> body) {
        long expected = ((Number) body.get("expectedRevisionNo")).longValue();
        UUID taskId =
                body.get("taskId") == null
                        ? null
                        : UUID.fromString(String.valueOf(body.get("taskId")));
        try {
            Map<String, Object> r =
                    editConfirm.confirm(projectId, planRevisionId, expected, "local-user", taskId);
            Map<String, Object> out = new LinkedHashMap<>(r);
            out.put("operationId", body.getOrDefault("clientActionId", UUID.randomUUID().toString()));
            out.put("confirmedAt", java.time.Instant.now().toString());
            return ResponseEntity.ok(out);
        } catch (PlanEditConfirmUseCase.InvalidPlanPatchException e) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "code",
                                    "PLAN_SCHEMA_INVALID",
                                    "message",
                                    e.code(),
                                    "retryable",
                                    false));
        } catch (PlanEditConfirmUseCase.StalePlanRevisionException e) {
            return ResponseEntity.status(409)
                    .body(
                            Map.of(
                                    "code",
                                    "STALE_PLAN_REVISION",
                                    "message",
                                    "STALE_PLAN_REVISION",
                                    "retryable",
                                    false));
        }
    }

    @PostMapping("/projects/{projectId}/plans/{planRevisionId}/edit")
    public ResponseEntity<Map<String, Object>> edit(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("planRevisionId") UUID planRevisionId,
            @RequestBody Map<String, Object> body) {
        long expected = ((Number) body.get("expectedRevisionNo")).longValue();
        Object patch = body.get("patch");
        String patchJson;
        try {
            if (patch instanceof String s) {
                patchJson = s;
            } else if (patch == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("code", "PLAN_PATCH_INVALID", "message", "PATCH_EMPTY", "retryable", false));
            } else {
                patchJson = objectMapper.writeValueAsString(patch);
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "PLAN_PATCH_INVALID", "message", "PATCH_SERIALIZE_FAILED", "retryable", false));
        }
        try {
            return ResponseEntity.ok(editConfirm.edit(projectId, planRevisionId, expected, patchJson));
        } catch (PlanEditConfirmUseCase.InvalidPlanPatchException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "PLAN_PATCH_INVALID", "message", e.code(), "retryable", false));
        } catch (PlanEditConfirmUseCase.StalePlanRevisionException e) {
            return ResponseEntity.status(409)
                    .body(Map.of("code", "STALE_PLAN_REVISION", "retryable", false));
        }
    }

    @PostMapping("/projects/{projectId}/articles/{articleId}/chapters/{chapterIndex}/edit")
    public ResponseEntity<Map<String, Object>> editChapter(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("articleId") UUID articleId,
            @PathVariable("chapterIndex") int chapterIndex,
            @RequestBody Map<String, Object> body) {
        String title = String.valueOf(body.getOrDefault("title", ""));
        String bodyMarkdown = String.valueOf(body.getOrDefault("bodyMarkdown", ""));
        try {
            var art = articles.ensureArticle(projectId);
            if (!art.articleId().equals(articleId)) {
                return ResponseEntity.status(404)
                        .body(Map.of("code", "ARTICLE_NOT_FOUND", "retryable", false));
            }
            UUID rev =
                    articles.saveChapterDraft(
                            articleId, chapterIndex, title, bodyMarkdown, "USER_EDIT");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("chapterRevisionId", rev);
            out.put("chapterIndex", chapterIndex);
            out.putAll(articles.articleProjection(projectId));
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "CHAPTER_EDIT_REJECTED", "message", e.getMessage()));
        }
    }

    @PostMapping("/projects/{projectId}/articles/{articleId}/complete")
    public ResponseEntity<Map<String, Object>> completeArticle(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("articleId") UUID articleId) {
        try {
            return ResponseEntity.ok(articles.completeArticle(projectId, articleId));
        } catch (ArticleApplicationService.IncompleteArticleException e) {
            return ResponseEntity.status(409)
                    .body(
                            Map.of(
                                    "code",
                                    "ARTICLE_INCOMPLETE",
                                    "message",
                                    e.getMessage(),
                                    "retryable",
                                    false));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
        }
    }
}

package com.writingagent.v2.agent.chapter;

import com.writingagent.v2.agent.support.ToolInvocationLedger;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Read-only confirmed outline + prior chapter snippets. Per-invocation ledger (not static). */
public class ReadConfirmedOutlineTool {

    public static final String TOOL_NAME = "read_confirmed_outline_context";
    public static final String CTX_MARK_PREFIX = "CTX-CH-";

    public record PriorChapter(int chapterIndex, String title, String bodySnippet) {}

    private final UUID projectId;
    private final UUID actionId;
    private final UUID articleId;
    private final int targetChapterIndex;
    private final String targetTitle;
    private final String researchGoal;
    private final List<String> outline;
    private final List<PriorChapter> priorChapters;
    private final String ctxMark;
    private final ToolInvocationLedger ledger = new ToolInvocationLedger();
    private volatile Consumer<String> onToolResultDetail;

    public ReadConfirmedOutlineTool(
            UUID projectId,
            UUID actionId,
            UUID articleId,
            int targetChapterIndex,
            String targetTitle,
            String researchGoal,
            List<String> outline,
            List<PriorChapter> priorChapters) {
        this.projectId = projectId;
        this.actionId = actionId;
        this.articleId = articleId;
        this.targetChapterIndex = targetChapterIndex;
        this.targetTitle = targetTitle == null ? "" : targetTitle;
        this.researchGoal = researchGoal == null ? "" : researchGoal;
        this.outline = List.copyOf(outline);
        this.priorChapters = List.copyOf(priorChapters);
        this.ctxMark = CTX_MARK_PREFIX + actionId.toString().substring(0, 8);
    }

    public ToolInvocationLedger ledger() {
        return ledger;
    }

    public void setOnToolResultDetail(Consumer<String> onToolResultDetail) {
        this.onToolResultDetail = onToolResultDetail;
    }

    @Deprecated
    public static int executorInvocations() {
        return -1;
    }

    @Deprecated
    public static void resetExecutorInvocations() {
        // no-op
    }

    public String ctxMark() {
        return ctxMark;
    }

    public UUID boundProjectId() {
        return projectId;
    }

    @Tool(
            name = TOOL_NAME,
            description =
                    "Read confirmed chapterOutline and prior chapter snippets."
                            + " Copy ctxMark into ChapterOutcome.contextMarkEcho.")
    public String readConfirmedOutlineContext(
            @ToolParam(name = "reason", description = "Why context is needed", required = false)
                    String reason) {
        StringBuilder outlineJson = new StringBuilder("[");
        for (int i = 0; i < outline.size(); i++) {
            if (i > 0) {
                outlineJson.append(',');
            }
            outlineJson
                    .append("{\"index\":")
                    .append(i + 1)
                    .append(",\"title\":\"")
                    .append(escape(outline.get(i)))
                    .append("\"}");
        }
        outlineJson.append(']');

        StringBuilder prior = new StringBuilder();
        for (int i = 0; i < priorChapters.size(); i++) {
            if (i > 0) {
                prior.append(',');
            }
            PriorChapter ch = priorChapters.get(i);
            prior.append("{\"chapterIndex\":")
                    .append(ch.chapterIndex())
                    .append(",\"title\":\"")
                    .append(escape(ch.title()))
                    .append("\",\"bodySnippet\":\"")
                    .append(escape(ch.bodySnippet()))
                    .append("\"}");
        }

        String json =
                """
                {"projectId":"%s","actionId":"%s","researchGoal":%s,"articleId":"%s","chapterCount":%d,"targetChapterIndex":%d,"targetTitle":%s,"chapterOutline":%s,"priorChapters":[%s],"ctxMark":"%s","documentsIndexed":0,"externalSourcesUsed":false,"note":"No retrieval; evidenceBasis must be USER_GOAL_AND_MODEL_SYNTHESIS"}
                """
                        .formatted(
                                projectId,
                                actionId,
                                jsonString(researchGoal),
                                articleId,
                                outline.size(),
                                targetChapterIndex,
                                jsonString(targetTitle),
                                outlineJson,
                                prior,
                                ctxMark);
        ledger.recordSuccess(projectId.toString(), json);
        Consumer<String> sink = onToolResultDetail;
        if (sink != null) {
            sink.accept(
                    "sha256="
                            + ledger.lastResultSha256()
                            + " projectId="
                            + ledger.boundProjectId()
                            + " invocations="
                            + ledger.invocationCount()
                            + " when=tool_return");
        }
        return json;
    }

    private static String jsonString(String s) {
        return "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

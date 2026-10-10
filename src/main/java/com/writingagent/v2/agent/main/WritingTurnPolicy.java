package com.writingagent.v2.agent.main;

import java.util.List;
import java.util.Map;

/** A narrow safety guard, not an intent router: chapter requests cannot silently replace a plan. */
public final class WritingTurnPolicy {
    private final boolean chapterRequest;
    public WritingTurnPolicy(String message) {
        String text = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        boolean allChapters = text.contains("全部章节") || text.contains("all chapters");
        boolean explicitRewrite = text.matches("(?s).*(重写|改写|rewrite|revise).*?(章|chapter).*?");
        boolean planOnly = (text.contains("计划") || text.contains("大纲") || text.contains("outline"))
                && !allChapters && !explicitRewrite && !text.contains("正文");
        chapterRequest = !planOnly && (text.matches("(?s).*(重写|改写|修改|rewrite|revise|生成|draft|generate).*?(第\\s*[0-9一二三四五六七八九十]+\\s*章|章节|chapter).*?")
                || allChapters);
    }
    public boolean allowPlanProposal() { return !chapterRequest; }

    /** Writing results are rendered from actual Tool receipts, never free-text fallbacks. */
    public String reply(String modelAnswer, List<Map<String, Object>> receipts, Map<String, Object> context) {
        if (receipts.isEmpty()) {
            if (!chapterRequest) return modelAnswer;
            return Boolean.TRUE.equals(context.get("planConfirmed"))
                    ? "本轮没有取得章节工具的保存回执，正文未修改。请查看执行过程，或使用正文页面的 AI 生成/重写按钮。"
                    : "当前计划尚未确认，正文未修改。请在计划页面点击「确认计划」，聊天回复不能代替确认。";
        }
        var summaries = new java.util.ArrayList<String>();
        for (var receipt : receipts) {
            String tool = String.valueOf(receipt.get("tool"));
            String code = receipt.get("code") == null ? "" : String.valueOf(receipt.get("code"));
            String outcome = String.valueOf(receipt.get("outcome"));
            if (Boolean.TRUE.equals(receipt.get("resultMayExist")) && outcome.contains("UNKNOWN")) {
                summaries.add("结果待核对，不能宣称已修改。请查看任务状态，不要重复生成。");
            } else if (!code.isBlank()) {
                summaries.add("PLAN_NOT_CONFIRMED".equals(code)
                        ? "计划尚未确认，本次未生成或修改正文。请在计划页面点击「确认计划」；聊天回复不能代替确认。"
                        : "本次操作未完成：" + code + "。没有把聊天正文当作已保存的修改。");
            } else if (RewriteTargetChapterTool.TOOL_NAME.equals(tool)) {
                Object revision = receipt.get("chapterRevisionId");
                if (revision != null && ("CHAPTER_REWRITTEN".equals(outcome) || "REPLAY".equals(outcome)
                        && "RESULT_RECORDED".equals(receipt.get("actionStatus")))) {
                    summaries.add("第" + receipt.get("chapterIndex") + "章已保存新修订（" + revision + "）。请在正文页面查看；其它章节未由此工具修改。");
                } else summaries.add("没有取得重写成功回执，正文未确认修改。请查看任务状态。");
            } else if (DraftConfirmedChaptersTool.TOOL_NAME.equals(tool)) {
                summaries.add(Boolean.TRUE.equals(receipt.get("complete"))
                        ? "确认大纲中的章节正文已全部就绪。请在正文页面检查，随后点击「确认全文完成」；正文齐全不等于已确认完成。"
                        : "章节生成未全部完成，请查看任务与逐章状态；不把部分正文当作全文完成。");
            } else if (ProposeWritingPlanTool.TOOL_NAME.equals(tool)) {
                summaries.add(receipt.get("planRevisionId") != null
                        ? "计划草案已保存（" + receipt.get("planRevisionId") + "），请在计划页面检查并点击「确认计划」。聊天不能代替确认。"
                        : "本轮没有取得计划保存回执，请查看任务状态。");
            }
        }
        return summaries.isEmpty() ? modelAnswer : String.join("\n\n", summaries);
    }
}

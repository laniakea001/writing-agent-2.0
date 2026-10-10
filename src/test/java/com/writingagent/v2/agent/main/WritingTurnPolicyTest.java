package com.writingagent.v2.agent.main;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WritingTurnPolicyTest {
    @Test void rewriteCannotCreatePlanOrUsePlainTextAsSavedChapter() {
        var policy = new WritingTurnPolicy("只重写第1章，末尾加入 SSE_MY_CHECK_1003，不改第2章");
        assertFalse(policy.allowPlanProposal());
        assertTrue(policy.reply("修改后的正文 SSE_MY_CHECK_1003", java.util.List.of(),
                Map.of("planConfirmed", true)).contains("未修改"));
    }
    @Test void unconfirmedRewriteDoesNotOfferChatConfirmationOrFallbackText() {
        var policy = new WritingTurnPolicy("只重写第1章");
        String reply = policy.reply("say Yes to confirm; here is the rewritten chapter", java.util.List.of(
                Map.of("tool", "rewrite_target_chapter", "code", "PLAN_NOT_CONFIRMED")), Map.of());
        assertTrue(reply.contains("点击"));
        assertFalse(reply.contains("here is"));
        assertFalse(reply.contains("Yes"));
    }
    @Test void persistedReceiptWinsOverModelTextAndUnknownNeverClaimsSuccess() {
        var policy = new WritingTurnPolicy("重写第1章");
        String reply = policy.reply("new draft plan", java.util.List.of(Map.of("tool", "rewrite_target_chapter",
                "chapterIndex", 1, "chapterRevisionId", "real-revision", "outcome", "CHAPTER_REWRITTEN")), Map.of());
        assertTrue(reply.contains("real-revision"));
        assertFalse(reply.contains("new draft"));
        String unknown = policy.reply("success", java.util.List.of(Map.of("tool", "rewrite_target_chapter",
                "outcome", "REPLAY_UNKNOWN", "resultMayExist", true)), Map.of());
        assertTrue(unknown.contains("核对"));
        assertFalse(unknown.contains("success"));
    }
    @Test void ordinaryChatAndExplicitPlanRemainAllowed() {
        assertTrue(new WritingTurnPolicy("生成两章计划").allowPlanProposal());
        assertTrue(new WritingTurnPolicy("修改计划里的章节安排").allowPlanProposal());
        assertEquals("你好", new WritingTurnPolicy("你是谁").reply("你好", java.util.List.of(), Map.of()));
    }
}

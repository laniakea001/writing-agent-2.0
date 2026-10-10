package com.writingagent.v2.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.writingagent.v2.domain.PlanOutcome;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Zero-model: DashScope text-JSON → structured metadata recovery. */
class PlanStructuredTextRecoveryTest {

    @Test
    void recoversValidPlanDraftJsonText() {
        String json =
                """
                {
                  "outcome": "PLAN_DRAFT_READY",
                  "contextMarkEcho": "CTX-MARK-abcd1234",
                  "evidenceBasis": "USER_GOAL_AND_MODEL_SYNTHESIS",
                  "plan": {
                    "researchQuestion": "q",
                    "goals": "g",
                    "scope": "s",
                    "chapterOutline": ["一", "二"],
                    "methodSuggestion": "m",
                    "expectedDeliverables": "d"
                  }
                }
                """;
        Msg raw = new AssistantMessage(json);
        assertFalse(raw.hasStructuredData());

        List<PlanAgentRuntime.TraceLine> lines = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        Msg recovered =
                PlanAgentRuntime.recoverStructuredFromTextIfNeeded(
                        raw, "CTX-MARK-abcd1234", lines, seq);

        assertTrue(recovered.hasStructuredData());
        PlanOutcome o = recovered.getStructuredData(PlanOutcome.class);
        assertEquals(PlanOutcome.PLAN_DRAFT_READY, o.outcome);
        assertEquals("CTX-MARK-abcd1234", o.contextMarkEcho);
        assertEquals(2, o.plan.chapterOutline.size());
        assertTrue(
                lines.stream()
                        .anyMatch(l -> "STRUCTURED_OUTPUT_TEXT_RECOVERED".equals(l.kind())));
    }

    @Test
    void rejectsCtxMarkMismatch() {
        String json =
                """
                {
                  "outcome": "PLAN_DRAFT_READY",
                  "contextMarkEcho": "CTX-MARK-wrong",
                  "evidenceBasis": "USER_GOAL_AND_MODEL_SYNTHESIS",
                  "plan": { "chapterOutline": ["一"] }
                }
                """;
        Msg raw = new AssistantMessage(json);
        List<PlanAgentRuntime.TraceLine> lines = new ArrayList<>();
        Msg out =
                PlanAgentRuntime.recoverStructuredFromTextIfNeeded(
                        raw, "CTX-MARK-abcd1234", lines, new AtomicInteger());
        assertFalse(out.hasStructuredData());
        assertTrue(
                lines.stream()
                        .anyMatch(l -> "STRUCTURED_TEXT_RECOVER_SKIP".equals(l.kind())));
    }

    @Test
    void stripsMarkdownFence() {
        assertTrue(
                PlanAgentRuntime.stripJsonFence("```json\n{\"a\":1}\n```").startsWith("{"));
    }
}

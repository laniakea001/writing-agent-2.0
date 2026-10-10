package com.writingagent.v2.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanOutcomeValidatorTest {

    @Test
    void draftRequiresNonEmptyOutlineItems() {
        PlanOutcome o = new PlanOutcome();
        o.outcome = PlanOutcome.PLAN_DRAFT_READY;
        o.evidenceBasis = PlanOutcome.SOURCE_BASIS;
        o.plan = new PlanOutcome.PlanBody();
        o.plan.chapterOutline = List.of();
        assertEquals("CHAPTER_OUTLINE_EMPTY", PlanOutcomeValidator.validateOutcome(o));

        o.plan.chapterOutline = List.of("好", "  ");
        assertEquals("CHAPTER_OUTLINE_BLANK_ITEM", PlanOutcomeValidator.validateOutcome(o));

        o.plan.chapterOutline = List.of("第一章", "第二章");
        assertNull(PlanOutcomeValidator.validateOutcome(o));
    }

    @Test
    void clarificationRequiresQuestions() {
        PlanOutcome o = new PlanOutcome();
        o.outcome = PlanOutcome.CLARIFICATION_REQUIRED;
        o.evidenceBasis = PlanOutcome.SOURCE_BASIS;
        assertEquals("CLARIFICATION_QUESTIONS_REQUIRED", PlanOutcomeValidator.validateOutcome(o));
        o.clarificationQuestions = List.of("需要什么范围？");
        assertNull(PlanOutcomeValidator.validateOutcome(o));
    }

    @Test
    void patchJsonRejectsEmptyOutlineAndMapToString() {
        assertEquals(
                "CHAPTER_OUTLINE_EMPTY",
                PlanOutcomeValidator.validatePatchJson(
                        "{\"outcome\":\"PLAN_DRAFT_READY\",\"evidenceBasis\":\"USER_GOAL_AND_MODEL_SYNTHESIS\",\"plan\":{\"chapterOutline\":[]}}"));
        assertNotNull(PlanOutcomeValidator.validatePatchJson("{plan=foo}"));
        assertNull(
                PlanOutcomeValidator.validatePatchJson(
                        "{\"outcome\":\"PLAN_DRAFT_READY\",\"evidenceBasis\":\"USER_GOAL_AND_MODEL_SYNTHESIS\",\"plan\":{\"researchQuestion\":\"q\",\"chapterOutline\":[\"A\",\"B\"]}}"));
    }
}

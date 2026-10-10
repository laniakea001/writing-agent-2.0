package com.writingagent.v2.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Single validator for native SO, text-JSON recovery, and user plan patches (M5).
 * evidenceBasis must match this stage's real capability declaration.
 */
public final class PlanOutcomeValidator {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private PlanOutcomeValidator() {}

    public static String validateOutcome(PlanOutcome o) {
        if (o == null || o.outcome == null || o.outcome.isBlank()) {
            return "OUTCOME_REQUIRED";
        }
        if (!PlanOutcome.PLAN_DRAFT_READY.equals(o.outcome)
                && !PlanOutcome.CLARIFICATION_REQUIRED.equals(o.outcome)) {
            return "OUTCOME_UNSUPPORTED";
        }
        if (o.evidenceBasis == null || !PlanOutcome.SOURCE_BASIS.equals(o.evidenceBasis)) {
            return "EVIDENCE_BASIS_INVALID";
        }
        if (PlanOutcome.PLAN_DRAFT_READY.equals(o.outcome)) {
            return validateDraftPlan(o.plan);
        }
        if (o.clarificationQuestions == null || o.clarificationQuestions.isEmpty()) {
            return "CLARIFICATION_QUESTIONS_REQUIRED";
        }
        for (String q : o.clarificationQuestions) {
            if (q == null || q.isBlank()) {
                return "CLARIFICATION_QUESTION_BLANK";
            }
        }
        return null;
    }

    public static String validateDraftPlan(PlanOutcome.PlanBody plan) {
        if (plan == null) {
            return "PLAN_BODY_REQUIRED";
        }
        if (plan.chapterOutline == null || plan.chapterOutline.isEmpty()) {
            return "CHAPTER_OUTLINE_EMPTY";
        }
        for (String title : plan.chapterOutline) {
            if (title == null || title.isBlank()) {
                return "CHAPTER_OUTLINE_BLANK_ITEM";
            }
        }
        return null;
    }

    /**
     * Accepts full PlanOutcome JSON or a payload shaped like persisted draft
     * ({@code outcome}/{@code plan}/… or bare {@code plan}/{@code chapterOutline}).
     */
    public static String validatePatchJson(String patchJson) {
        if (patchJson == null || patchJson.isBlank()) {
            return "PATCH_EMPTY";
        }
        try {
            JsonNode root = JSON.readTree(patchJson);
            if (root == null || root.isNull()) {
                return "PATCH_NOT_JSON";
            }
            // Reject Java Map#toString artifacts
            if (!root.isObject() && !root.isArray()) {
                return "PATCH_NOT_OBJECT";
            }
            if (root.has("outcome") || root.has("plan") || root.has("clarificationQuestions")) {
                PlanOutcome o = JSON.treeToValue(root, PlanOutcome.class);
                if (o.evidenceBasis == null || o.evidenceBasis.isBlank()) {
                    o.evidenceBasis = PlanOutcome.SOURCE_BASIS;
                }
                if (o.outcome == null || o.outcome.isBlank()) {
                    o.outcome = PlanOutcome.PLAN_DRAFT_READY;
                }
                return validateOutcome(o);
            }
            PlanOutcome.PlanBody body = new PlanOutcome.PlanBody();
            if (root.has("chapterOutline")) {
                body.chapterOutline =
                        JSON.convertValue(
                                root.get("chapterOutline"),
                                JSON.getTypeFactory()
                                        .constructCollectionType(List.class, String.class));
            } else if (root.has("plan") && root.get("plan").has("chapterOutline")) {
                body = JSON.treeToValue(root.get("plan"), PlanOutcome.PlanBody.class);
            } else {
                return "CHAPTER_OUTLINE_MISSING";
            }
            if (root.has("researchQuestion") && body.researchQuestion == null) {
                body.researchQuestion = root.get("researchQuestion").asText(null);
            }
            return validateDraftPlan(body);
        } catch (Exception e) {
            return "PATCH_PARSE_FAILED";
        }
    }

    public static boolean isValid(PlanOutcome o) {
        return validateOutcome(o) == null;
    }
}

package com.writingagent.v2.domain;

import java.util.ArrayList;
import java.util.List;

/** Agent-internal structured outcome (distinct from Workbench API outcome). */
public class PlanOutcome {

    public static final String CLARIFICATION_REQUIRED = "CLARIFICATION_REQUIRED";
    public static final String PLAN_DRAFT_READY = "PLAN_DRAFT_READY";
    public static final String SOURCE_BASIS = "USER_GOAL_AND_MODEL_SYNTHESIS";

    public String outcome;
    public List<String> clarificationQuestions = new ArrayList<>();
    public PlanBody plan;
    public String evidenceBasis;
    public List<String> limitations = new ArrayList<>();
    public String contextMarkEcho;

    public static class PlanBody {
        public String researchQuestion;
        public String goals;
        public String scope;
        public List<String> chapterOutline = new ArrayList<>();
        public String methodSuggestion;
        public String expectedDeliverables;
        public List<String> risksOrAssumptions = new ArrayList<>();
    }
}

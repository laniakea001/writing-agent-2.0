package com.writingagent.v2.domain;

import java.util.ArrayList;
import java.util.List;

/** Agent-internal structured chapter draft (Application persists). */
public class ChapterOutcome {

    public static final String CHAPTER_DRAFT_READY = "CHAPTER_DRAFT_READY";
    public static final String SOURCE_BASIS = "USER_GOAL_AND_MODEL_SYNTHESIS";

    public String outcome;
    public int chapterIndex;
    public String title;
    public String bodyMarkdown;
    public String evidenceBasis;
    public List<String> limitations = new ArrayList<>();
    public String contextMarkEcho;
}

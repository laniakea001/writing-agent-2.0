package com.writingagent.v2.application.generation;

/**
 * Where stage persistence should put human-readable outcome text.
 * ARTIFACT_ONLY: ToolResult / run_event / revision rows — no second chat turn.
 */
public enum PresentationSink {
    CHAT_AND_ARTIFACT,
    ARTIFACT_ONLY
}

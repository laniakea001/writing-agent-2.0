package com.writingagent.v2.application.generation;

/**
 * Who invoked a shared plan/chapter generation module.
 * Drives product-message policy — not a pile of booleans.
 */
public enum InvocationOrigin {
    /** Legacy workbench button HTTP path: outer records user + stage assistant chat. */
    WORKBENCH_BUTTON,
    /** Main Agent Tool path: root turn owns user + final assistant; module emits artifacts only. */
    MAIN_AGENT_TOOL
}

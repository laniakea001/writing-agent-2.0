package com.writingagent.v2.domain;

/** Article lifecycle for full-article MVP. */
public enum ArticleStatus {
    /** Created; chapter generation in progress or not started. */
    DRAFTING,
    /** Some confirmed-outline chapters missing or failed. */
    PARTIAL,
    /** All outline chapters have non-empty CURRENT bodies; not user-completed. */
    READY_FOR_COMPLETE,
    /** User confirmed whole article complete. */
    COMPLETE
}

package com.writingagent.v2.domain;

/** Stable business rejection; the HTTP adapter decides how to render it. */
public final class WritingRuleException extends RuntimeException {
    private final int status;
    public WritingRuleException(int status, String code) { super(code); this.status=status; }
    public int status() { return status; }
    public String code() { return getMessage(); }
}

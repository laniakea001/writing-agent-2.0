package com.writingagent.v2.runtime;

public final class RunContractException extends IllegalStateException {
    private final String code;
    public RunContractException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}

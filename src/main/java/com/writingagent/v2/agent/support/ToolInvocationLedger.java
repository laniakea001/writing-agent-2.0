package com.writingagent.v2.agent.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-invocation Tool execution ledger (not a static global). */
public final class ToolInvocationLedger {

    private final AtomicInteger invocations = new AtomicInteger();
    private volatile String lastResultSha256 = "";
    private volatile String lastResultSnippet = "";
    private volatile String boundProjectId = "";

    public void recordSuccess(String boundProjectId, String rawResultJson) {
        this.boundProjectId = boundProjectId == null ? "" : boundProjectId;
        invocations.incrementAndGet();
        String raw = rawResultJson == null ? "" : rawResultJson;
        lastResultSha256 = sha256(raw);
        lastResultSnippet = raw.length() > 180 ? raw.substring(0, 180) + "…" : raw;
    }

    public int invocationCount() {
        return invocations.get();
    }

    public String lastResultSha256() {
        return lastResultSha256;
    }

    public String lastResultSnippet() {
        return lastResultSnippet;
    }

    public String boundProjectId() {
        return boundProjectId;
    }

    public boolean executed() {
        return invocations.get() > 0;
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return "hash_unavailable";
        }
    }
}

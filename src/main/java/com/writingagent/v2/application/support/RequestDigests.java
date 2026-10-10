package com.writingagent.v2.application.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Pure digest helpers for action idempotency and content hashing. Not infrastructure. */
public final class RequestDigests {

    private RequestDigests() {}

    public static String of(
            String projectId,
            String conversationId,
            String taskId,
            String operation,
            String message,
            Long expectedProjectVersion) {
        String raw =
                String.join(
                        "|",
                        nullToEmpty(projectId),
                        nullToEmpty(conversationId),
                        nullToEmpty(taskId),
                        nullToEmpty(operation),
                        normalizeMessage(message),
                        expectedProjectVersion == null ? "" : expectedProjectVersion.toString());
        return sha256(raw);
    }

    private static String normalizeMessage(String message) {
        return message == null ? "" : message.trim().replaceAll("\\s+", " ");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String contentHash(String json) {
        return sha256(Objects.requireNonNull(json, "json"));
    }
}

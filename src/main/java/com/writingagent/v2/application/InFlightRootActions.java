package com.writingagent.v2.application;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Process-local registry of in-flight root ASSISTANT_TURN actions.
 *
 * <p>Claim insertion and register share a per-clientAction lock so a concurrent same-key follower
 * cannot observe CLAIMED without also observing in-flight (D0 / A1-F3).
 */
@Component
public class InFlightRootActions {

    private final ConcurrentMap<UUID, Long> startedAtMs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> clientLocks = new ConcurrentHashMap<>();

    public Object lockForClientKey(String clientKey) {
        return clientLocks.computeIfAbsent(clientKey, k -> new Object());
    }

    public static String clientKey(UUID projectId, String clientActionId) {
        return projectId + "|" + clientActionId;
    }

    public void register(UUID actionId) {
        if (actionId != null) {
            startedAtMs.put(actionId, System.currentTimeMillis());
        }
    }

    public boolean isInFlight(UUID actionId) {
        return actionId != null && startedAtMs.containsKey(actionId);
    }

    public void clear(UUID actionId) {
        if (actionId != null) {
            startedAtMs.remove(actionId);
        }
    }

    /** Test inject: sleep while still holding the claim/register lock (follower blocks on lock). */
    public static void injectClaimRegisterGapIfConfigured() {
        String env = System.getenv("WA2_INJECT_CLAIM_REGISTER_GAP_MS");
        if (env == null || env.isBlank()) {
            return;
        }
        try {
            long ms = Long.parseLong(env.trim());
            if (ms > 0) {
                Thread.sleep(ms);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (NumberFormatException ignored) {
            // no-op
        }
    }
}

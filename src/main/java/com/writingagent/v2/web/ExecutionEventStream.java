package com.writingagent.v2.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.ExecutionEventQuery;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Durable replay + live SSE, fed only by committed Run events. */
@Component
public class ExecutionEventStream {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final ExecutionEventQuery query;
    private final com.writingagent.v2.runtime.RunJournal journal;
    private final com.writingagent.v2.runtime.RunReplay replay;
    private final java.util.concurrent.ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "writing-execution-sse");
                thread.setDaemon(true);
                return thread;
            });

    /** Legacy-only constructor retained for archived protocol-1 tests. */
    public ExecutionEventStream(ExecutionEventQuery query) { this(query, null, null); }

    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionEventStream(ExecutionEventQuery query, com.writingagent.v2.runtime.RunJournal journal,
            com.writingagent.v2.runtime.RunReplay replay) {
        this.query = query; this.journal = journal; this.replay = replay;
    }

    public java.util.List<com.writingagent.v2.application.port.TaskRunPort.ExecutionView> list(
            UUID projectId, UUID conversationId) { return query.list(projectId, conversationId); }

    public SseEmitter subscribe(UUID projectId, UUID conversationId, String action,
            String cursor) {
        query.validate(projectId, conversationId);
        return subscribeInternal(projectId,conversationId,action,cursor,null);
    }

    /** Caller validates workspace ownership; registration is already durable. */
    public SseEmitter subscribeRoot(UUID root,String cursor) {
        var scope=journal.scope(root);
        return subscribeInternal(scope.projectId(),scope.conversationId(),null,cursor,root);
    }
    private SseEmitter subscribeInternal(UUID projectId,UUID conversationId,String action,String cursor,UUID registeredRoot) {
        Map<String, Long> positions = new LinkedHashMap<>();
        final String[] journalCursor = {cursor};
        final boolean[] legacyCursorLoaded = {false};
        SseEmitter emitter = new SseEmitter(600_000L);
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable cleanup = () -> { closed.set(true); var job = future.get(); if (job != null) job.cancel(false); };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> { cleanup.run(); emitter.complete(); });
        emitter.onError(ignored -> cleanup.run());
        final long[] ticks = {0};
        var job = scheduler.scheduleWithFixedDelay(() -> {
            try {
                UUID root = registeredRoot!=null?registeredRoot:journal == null ? null : query.rootRunId(projectId, conversationId, action);
                if (journal != null && root != null && journal.get(root).protocolVersion() == 2) {
                    var batch = replay.next(projectId, conversationId, root, journalCursor[0]);
                    var trustedScope=journal.scope(root);
                    for (var delivery : batch.deliveries()) {
                        var event = delivery.event();
                        var data = new LinkedHashMap<String, Object>();
                        data.put("schemaVersion", 2);
                        data.put("workspaceId",trustedScope.workspaceId());
                        data.put("projectId", projectId); data.put("conversationId", conversationId);
                        data.put("rootRunId", root); data.put("runId", root);
                        data.put("sourceRunId", event.runId()); data.put("parentRunId", event.parentRunId());
                        data.put("parentToolCallId", event.parentToolCallId());
                        data.put("sequence", event.sequence()); data.put("outputType", event.outputType());
                        data.put("rawEventType", event.rawEventType()); data.put("toolCallId", event.toolCallId());
                        data.put("invocationId", event.invocationId()); data.put("safePayload", event.safePayload());
                        data.put("createdAt", event.createdAt());
                        emitter.send(SseEmitter.event().name("execution-event").id(delivery.cursor()).data(data));
                    }
                    journalCursor[0] = batch.cursor(); // Includes unpublished internal events.
                    if (batch.complete()) {
                        var end = new LinkedHashMap<String, Object>();
                        end.put("schemaVersion", 2); end.put("runId", root);
                        end.put("status", batch.run().status());
                        end.put("actionStatus", switch(batch.run().status()) {
                            case SUCCEEDED -> "RESULT_RECORDED";
                            case UNKNOWN -> "UNKNOWN_GENERATION";
                            default -> "FAILED";
                        });
                        end.put("terminalManifest", batch.run().terminalManifest());
                        emitter.send(SseEmitter.event().name("execution-end").id(batch.cursor()).data(end));
                        cleanup.run(); emitter.complete();
                    } else if (ticks[0]++ % 20 == 0) emitter.send(SseEmitter.event().comment("heartbeat"));
                    return;
                }
                if (journal != null && root == null) {
                    if (ticks[0]++ % 20 == 0) emitter.send(SseEmitter.event().comment("awaiting registration"));
                    return;
                }
                if (!legacyCursorLoaded[0]) { positions.putAll(decodeCursor(cursor)); legacyCursorLoaded[0] = true; }
                var snapshot = query.read(projectId, conversationId, action);
                for (var event : deliveryOrder(snapshot.events())) {
                    String source = event.runId().toString();
                    if (event.sequence() <= positions.getOrDefault(source, 0L)) continue;
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("runId", snapshot.rootRunId());
                    data.put("sourceRunId", event.runId());
                    data.put("parentRunId", event.parentRunId());
                    data.put("sequence", event.sequence());
                    data.put("operation", event.operation());
                    data.put("kind", event.eventType());
                    data.put("toolCallId", event.toolCallId());
                    data.put("summary", event.summary());
                    data.put("createdAt", event.createdAt());
                    positions.put(source, event.sequence());
                    emitter.send(SseEmitter.event().name("execution-event")
                            .id(encodeCursor(positions)).data(data));
                }
                if (snapshot.terminal()) {
                    Map<String, Object> terminal = new LinkedHashMap<>();
                    terminal.put("runId", snapshot.rootRunId());
                    terminal.put("actionStatus", snapshot.actionStatus());
                    terminal.put("resultMayExist", snapshot.resultMayExist());
                    terminal.put("errorCode", snapshot.errorCode());
                    emitter.send(SseEmitter.event().name("execution-end").data(terminal));
                    cleanup.run();
                    emitter.complete();
                } else if (ticks[0]++ % 20 == 0) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                }
            } catch (Exception error) {
                cleanup.run();
                if (error instanceof ResponseStatusException || error instanceof com.writingagent.v2.runtime.RunContractException) {
                    try {
                        String code = error instanceof ResponseStatusException rejected ? rejected.getReason()
                                : ((com.writingagent.v2.runtime.RunContractException)error).code();
                        emitter.send(SseEmitter.event().name("execution-error")
                                .data(Map.of("code", code == null ? "EVENT_ACCESS_REJECTED" : code)));
                    } catch (Exception disconnected) { /* No business action is cancelled. */ }
                }
                emitter.completeWithError(error);
            }
        }, 100, 500, TimeUnit.MILLISECONDS);
        future.set(job);
        if (closed.get()) job.cancel(false);
        return emitter;
    }

    static Map<String, Long> decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return new LinkedHashMap<>();
        try {
            if (cursor.length() > 16384) throw new IllegalArgumentException("too long");
            Map<String, Long> parsed = JSON.readValue(Base64.getUrlDecoder().decode(cursor),
                    new TypeReference<Map<String, Long>>() {});
            if (parsed.size() > 128) throw new IllegalArgumentException("too many runs");
            for (var item : parsed.entrySet()) {
                UUID.fromString(item.getKey());
                if (item.getValue() == null || item.getValue() < 0) throw new IllegalArgumentException("bad sequence");
            }
            return new LinkedHashMap<>(parsed);
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_CURSOR");
        }
    }

    static String encodeCursor(Map<String, Long> positions) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(JSON.writeValueAsString(positions).getBytes(StandardCharsets.UTF_8));
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }

    /** Merge source runs by timestamp without ever advancing past an earlier source sequence. */
    static java.util.List<com.writingagent.v2.application.port.TaskRunPort.RunEventView> deliveryOrder(
            java.util.List<com.writingagent.v2.application.port.TaskRunPort.RunEventView> events) {
        var queues = new LinkedHashMap<UUID,
                java.util.ArrayDeque<com.writingagent.v2.application.port.TaskRunPort.RunEventView>>();
        events.stream().sorted(java.util.Comparator.comparingLong(
                com.writingagent.v2.application.port.TaskRunPort.RunEventView::sequence))
                .forEach(e -> queues.computeIfAbsent(e.runId(), ignored -> new java.util.ArrayDeque<>()).add(e));
        var order = java.util.Comparator
                .comparing(com.writingagent.v2.application.port.TaskRunPort.RunEventView::createdAt)
                .thenComparing(e -> e.runId().toString())
                .thenComparingLong(com.writingagent.v2.application.port.TaskRunPort.RunEventView::sequence);
        var result = new java.util.ArrayList<com.writingagent.v2.application.port.TaskRunPort.RunEventView>();
        while (!queues.isEmpty()) {
            var next = queues.values().stream().map(java.util.ArrayDeque::peek).min(order).orElseThrow();
            result.add(next);
            var queue = queues.get(next.runId()); queue.remove();
            if (queue.isEmpty()) queues.remove(next.runId());
        }
        return result;
    }

    @PreDestroy
    public void close() { scheduler.shutdownNow(); }
}

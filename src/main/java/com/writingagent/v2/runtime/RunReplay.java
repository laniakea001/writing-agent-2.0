package com.writingagent.v2.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read-only replay: page/cursor movement never claims, resumes, or settles execution. */
@Service
public class RunReplay {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RunJournal journal;

    public RunReplay(RunJournal journal) { this.journal=journal; }

    public record Cursor(UUID rootRunId, Map<UUID,Long> positions) {}
    public record Delivery(RunJournal.Event event, String cursor) {}
    public record Batch(RunJournal.Run run, List<Delivery> deliveries, String cursor, boolean complete) {}

    public RunJournal.Run read(UUID projectId, UUID conversationId, UUID runId) {
        var scope=journal.scope(runId);
        if (!java.util.Objects.equals(scope.projectId(),projectId) || !scope.conversationId().equals(conversationId))
            throw new RunContractException("RUN_SCOPE_MISMATCH");
        var run=journal.get(runId);
        if (!runId.equals(run.rootRunId())) throw new RunContractException("ROOT_RUN_REQUIRED");
        return run;
    }

    public Batch next(UUID projectId, UUID conversationId, UUID root, String cursorText) {
        RunJournal.Run before=read(projectId,conversationId,root);
        Cursor cursor=decode(root,cursorText);
        Map<UUID,Long> positions=new LinkedHashMap<>(cursor.positions());
        List<RunJournal.Run> tree=journal.tree(root);
        Map<UUID,RunJournal.Run> byId=new LinkedHashMap<>();
        tree.forEach(r -> byId.put(r.runId(),r));
        for (var item:positions.entrySet()) {
            var source=byId.get(item.getKey());
            if (source==null || item.getValue()<0 || item.getValue()>source.latestSequence())
                throw new RunContractException("INVALID_EVENT_CURSOR");
        }
        // Each source is paged independently; no global-time ordering guarantee.
        List<Delivery> deliveries=new ArrayList<>();
        for (var source:tree) {
            for (var event:journal.page(source.runId(),positions.getOrDefault(source.runId(),0L),256)) {
                positions.put(source.runId(),event.sequence());
                if (event.publishRequired()) deliveries.add(new Delivery(event,encode(root,positions)));
            }
        }
        RunJournal.Run after=read(projectId,conversationId,root);
        boolean complete=after.terminal() && !after.terminalManifest().isEmpty()
                && after.terminalManifest().entrySet().stream()
                .allMatch(e -> positions.getOrDefault(e.getKey(),0L)>=e.getValue());
        // Recheck the durable tails, not a transient action status.
        if (complete) {
            for (var source:journal.tree(root)) {
                if (!source.terminal() || !after.terminalManifest().containsKey(source.runId())
                        || source.latestSequence()!=after.terminalManifest().get(source.runId()))
                    throw new RunContractException("INVALID_TERMINAL_MANIFEST");
            }
        }
        return new Batch(after,List.copyOf(deliveries),encode(root,positions),complete);
    }

    static Cursor decode(UUID root, String text) {
        if (text==null || text.isBlank()) return new Cursor(root,Map.of());
        try {
            if (text.length()>65536) throw new IllegalArgumentException();
            Map<String,Object> body=JSON.readValue(Base64.getUrlDecoder().decode(text),new TypeReference<>() {});
            if (!Integer.valueOf(2).equals(body.get("version")) || !root.toString().equals(body.get("rootRunId")))
                throw new IllegalArgumentException();
            Object raw=body.get("positions");
            if (!(raw instanceof Map<?,?> map) || map.size()>512) throw new IllegalArgumentException();
            Map<UUID,Long> positions=new LinkedHashMap<>();
            for(var item:map.entrySet()) {
                if (!(item.getValue() instanceof Number number)
                        || number.doubleValue()!=number.longValue() || number.longValue()<0)
                    throw new IllegalArgumentException();
                positions.put(UUID.fromString(String.valueOf(item.getKey())),number.longValue());
            }
            return new Cursor(root,Map.copyOf(positions));
        } catch(Exception invalid) { throw new RunContractException("INVALID_EVENT_CURSOR"); }
    }

    static String encode(UUID root,Map<UUID,Long> positions) {
        try {
            String body=JSON.writeValueAsString(Map.of("version",2,"rootRunId",root,"positions",positions));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        } catch(Exception invalid) { throw new RunContractException("INVALID_EVENT_CURSOR"); }
    }
}

package com.writingagent.v2.web;

import com.writingagent.v2.application.capability.StageAiRuns;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/writing/v2")
public class StageAiController {
    private final StageAiRuns runs;
    private final ExecutionEventStream streams;
    public StageAiController(StageAiRuns runs,ExecutionEventStream streams){this.runs=runs;this.streams=streams;}
    @PostMapping("/projects/{projectId}/ai-actions")
    public ResponseEntity<Map<String,Object>> submit(@PathVariable UUID projectId,@RequestBody StageAiRuns.Request request){var r=runs.submit(projectId,request);return ResponseEntity.status(Set.of("QUEUED","RUNNING").contains(r.get("status"))?202:200).body(r);}
    @GetMapping("/runs/{runId}") public Map<String,Object> read(@PathVariable UUID runId){return runs.read(runId);}
    public record ReconcileRequest(String clientActionId) {}
    @PostMapping("/runs/{runId}/reconcile") public Map<String,Object> reconcile(@PathVariable UUID runId,@RequestBody ReconcileRequest request){return runs.reconcile(runId,request.clientActionId());}
    @GetMapping("/projects/{projectId}/ai-actions/lookup") public Map<String,Object> lookup(@PathVariable UUID projectId,@RequestParam String clientActionId){return runs.lookup(projectId,clientActionId);}
    @GetMapping("/workspaces/{workspaceId}/runs") public Map<String,Object> list(@PathVariable UUID workspaceId){return Map.of("items",runs.list(workspaceId));}
    @GetMapping(value="/runs/{runId}/events",produces=org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(@PathVariable UUID runId,
            @RequestParam(required=false) String cursor,@RequestHeader(name="Last-Event-ID",required=false) String lastEventId,
            jakarta.servlet.http.HttpServletResponse response){
        var run=runs.read(runId);response.setHeader("Cache-Control","no-cache, no-transform");response.setHeader("X-Accel-Buffering","no");
        return streams.subscribeRoot(runId,lastEventId==null||lastEventId.isBlank()?cursor:lastEventId);
    }
}

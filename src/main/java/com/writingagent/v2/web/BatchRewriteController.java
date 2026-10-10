package com.writingagent.v2.web;

import com.writingagent.v2.application.capability.BatchRewriteWorkflow;
import com.writingagent.v2.application.capability.StageAiRuns;
import com.writingagent.v2.application.port.BatchRewritePort.Batch;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/writing/v2")
public class BatchRewriteController {
 private final BatchRewriteWorkflow batches;
 private final StageAiRuns runs;
 public BatchRewriteController(BatchRewriteWorkflow batches,StageAiRuns runs){this.batches=batches;this.runs=runs;}
 @PostMapping("/projects/{projectId}/batch-rewrites")
 public ResponseEntity<Batch> submit(@PathVariable UUID projectId,@RequestBody BatchRewriteWorkflow.Request request){return ResponseEntity.accepted().body(runs.submitBatch(projectId,request));}
 @GetMapping("/batch-rewrites/{batchId}")
 public Batch read(@PathVariable UUID batchId){return batches.read(batchId);}
 @GetMapping("/projects/{projectId}/batch-rewrites/active")
 public java.util.Map<String,Object> active(@PathVariable UUID projectId){return java.util.Map.of("items",batches.active(projectId).stream().toList());}
 @GetMapping("/projects/{projectId}/batch-rewrites/lookup")
 public Batch lookup(@PathVariable UUID projectId,@RequestParam String clientActionId){return batches.lookup(projectId,clientActionId);}
 @PostMapping("/batch-rewrites/{batchId}/controls")
 public com.fasterxml.jackson.databind.JsonNode control(@PathVariable UUID batchId,@RequestBody BatchRewriteWorkflow.Control request){return batches.control(batchId,request);}
 public record StoppedOwner(long ownerPid) {}
 @PostMapping("/batch-rewrites/{batchId}/reconcile-stopped")
 public Batch reconcile(@PathVariable UUID batchId,@RequestBody StoppedOwner request){return batches.reconcileStopped(batchId,request.ownerPid());}
}

package com.writingagent.v2.web;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/writing/v2")
public class ExecutionEventController {
    private final ExecutionEventStream streams;
    private final com.writingagent.v2.runtime.RunJournal journal;
    public ExecutionEventController(ExecutionEventStream streams,
            com.writingagent.v2.runtime.RunJournal journal) { this.streams = streams; this.journal = journal; }

    @GetMapping("/projects/{projectId}/runs/{runId}")
    public com.writingagent.v2.runtime.RunJournal.Run run(@PathVariable UUID projectId,
            @PathVariable UUID runId, @RequestParam UUID conversationId) {
        var scope = journal.scope(runId);
        if (!projectId.equals(scope.projectId()) || !conversationId.equals(scope.conversationId()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return journal.get(runId);
    }

    @GetMapping("/projects/{projectId}/executions")
    public java.util.List<com.writingagent.v2.application.port.TaskRunPort.ExecutionView> executions(
            @PathVariable UUID projectId, @RequestParam UUID conversationId) {
        return streams.list(projectId, conversationId);
    }

    @GetMapping(value="/projects/{projectId}/execution-events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID projectId, @RequestParam UUID conversationId,
            @RequestParam String clientActionId,
            @RequestParam(required=false) String cursor,
            @RequestHeader(name="Last-Event-ID", required=false) String lastEventId,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return streams.subscribe(projectId, conversationId, clientActionId,
                lastEventId == null || lastEventId.isBlank() ? cursor : lastEventId);
    }
}

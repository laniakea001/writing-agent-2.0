package com.writingagent.v2.application;

import com.writingagent.v2.application.port.ActionLedgerPort;
import com.writingagent.v2.application.port.ConversationPort;
import com.writingagent.v2.application.port.TaskRunPort;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read-only exact-action projection. Subscribing never claims or resumes an action. */
@Service
public class ExecutionEventQuery {
    public record Snapshot(UUID rootRunId, String actionStatus, boolean resultMayExist,
            String errorCode, boolean terminal, List<TaskRunPort.RunEventView> events) {}

    private final ActionLedgerPort actions;
    private final ConversationPort conversations;
    private final TaskRunPort runs;
    private final InFlightRootActions inFlight;

    public ExecutionEventQuery(ActionLedgerPort actions, ConversationPort conversations,
            TaskRunPort runs, InFlightRootActions inFlight) {
        this.actions = actions;
        this.conversations = conversations;
        this.runs = runs;
        this.inFlight = inFlight;
    }

    public void validate(UUID projectId, UUID conversationId) {
        if (!conversations.belongsToProject(conversationId, projectId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONVERSATION_PROJECT_MISMATCH");
        }
    }

    public Snapshot read(UUID projectId, UUID conversationId, String clientActionId) {
        validate(projectId, conversationId);
        var row = actions.find(projectId, clientActionId);
        if (row.isEmpty()) return new Snapshot(null, "AWAITING_ACTION", false, null, false, List.of());
        var action = row.get();
        if (action.conversationId() != null && !conversationId.equals(action.conversationId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACTION_CONVERSATION_MISMATCH");
        }
        boolean terminal = !"CLAIMED".equals(action.status());
        String status = action.status();
        List<TaskRunPort.RunEventView> events = action.runId() == null
                ? List.of() : runs.listExecutionEvents(action.runId());
        // Missing process-local ownership is not proof of a stopped executor. Reads never settle.
        return new Snapshot(action.runId(), status,
                action.resultMayExist() || "UNKNOWN_GENERATION".equals(status),
                action.errorCode(), terminal, events);
    }

    public java.util.List<TaskRunPort.ExecutionView> list(UUID projectId, UUID conversationId) {
        validate(projectId, conversationId);
        return runs.listExecutions(projectId, conversationId);
    }

    public UUID rootRunId(UUID projectId, UUID conversationId, String actionId) {
        validate(projectId, conversationId);
        var action = actions.find(projectId, actionId);
        if (action.isEmpty()) return null;
        if (action.get().conversationId() != null && !conversationId.equals(action.get().conversationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACTION_CONVERSATION_MISMATCH");
        return action.get().runId();
    }

}

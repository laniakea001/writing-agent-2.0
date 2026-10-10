package com.writingagent.v2.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.writingagent.v2.application.port.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ExecutionEventQueryTest {
    @Test
    void subscriptionRejectsForeignConversationWithoutReadingOrWritingAction() {
        var actions = mock(ActionLedgerPort.class); var conversations = mock(ConversationPort.class);
        var runs = mock(TaskRunPort.class);
        var query = new ExecutionEventQuery(actions, conversations, runs, new InFlightRootActions());
        assertThrows(ResponseStatusException.class,
                () -> query.read(UUID.randomUUID(), UUID.randomUUID(), "action"));
        verifyNoInteractions(actions, runs);
    }

    @Test
    void missingActionWaitsWithoutCreatingRun() {
        var p = UUID.randomUUID(); var c = UUID.randomUUID();
        var actions = mock(ActionLedgerPort.class); var conversations = mock(ConversationPort.class);
        var runs = mock(TaskRunPort.class);
        when(conversations.belongsToProject(c, p)).thenReturn(true);
        when(actions.find(p, "action")).thenReturn(Optional.empty());
        var result = new ExecutionEventQuery(actions, conversations, runs, new InFlightRootActions())
                .read(p, c, "action");
        assertFalse(result.terminal()); assertEquals("AWAITING_ACTION", result.actionStatus());
        verify(actions).find(p, "action"); verifyNoMoreInteractions(actions); verifyNoInteractions(runs);
    }

    @Test
    void rejectsActionBoundToAnotherConversationEvenInsideSameProject() {
        var p = UUID.randomUUID(); var c = UUID.randomUUID();
        var actions = mock(ActionLedgerPort.class); var conversations = mock(ConversationPort.class);
        var runs = mock(TaskRunPort.class);
        when(conversations.belongsToProject(c, p)).thenReturn(true);
        when(actions.find(p, "action")).thenReturn(Optional.of(new ActionLedgerPort.ActionLedgerView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CLAIMED", null,
                false, null, null, null)));
        assertThrows(ResponseStatusException.class, () ->
                new ExecutionEventQuery(actions, conversations, runs, new InFlightRootActions())
                        .read(p, c, "action"));
        verifyNoInteractions(runs);
    }

    @Test
    void missingLocalOwnerDoesNotSettleAnAuthoritativelyClaimedRun() {
        var p = UUID.randomUUID(); var c = UUID.randomUUID(); var r = UUID.randomUUID();
        var action = UUID.randomUUID();
        var actions = mock(ActionLedgerPort.class); var conversations = mock(ConversationPort.class);
        var runs = mock(TaskRunPort.class);
        when(conversations.belongsToProject(c, p)).thenReturn(true);
        when(actions.find(p, "action")).thenReturn(Optional.of(new ActionLedgerPort.ActionLedgerView(
                c, UUID.randomUUID(), r, "CLAIMED", null, false, null, null, null)));
        when(actions.findClaimKeys(p, "action")).thenReturn(Optional.of(new ActionLedgerPort.ClaimRow(
                action, "digest", com.writingagent.v2.domain.ActionStatus.CLAIMED, r)));
        when(runs.listExecutionEvents(r)).thenReturn(java.util.List.of(new TaskRunPort.RunEventView(
                r, null, "ASSISTANT_TURN", 1, "RUN_STARTED", null, "start", java.time.Instant.EPOCH)));
        var result = new ExecutionEventQuery(actions, conversations, runs, new InFlightRootActions())
                .read(p, c, "action");
        assertEquals("CLAIMED", result.actionStatus());
        assertFalse(result.terminal()); assertFalse(result.resultMayExist());
        assertEquals(r, result.rootRunId());
        assertEquals(1, result.events().size());
        verify(actions).find(p, "action");
        verifyNoMoreInteractions(actions);
    }
}

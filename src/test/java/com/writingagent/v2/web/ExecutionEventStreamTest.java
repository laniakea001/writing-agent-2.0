package com.writingagent.v2.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.writingagent.v2.application.ExecutionEventQuery;
import com.writingagent.v2.application.port.TaskRunPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** No Spring context, database or model calls. Exercises the actual HTTP SSE adapter. */
class ExecutionEventStreamTest {
    @Test
    void publishesBeforeCompletionAndResumesWithoutDuplicates() throws Exception {
        var query = mock(ExecutionEventQuery.class);
        var project = UUID.randomUUID(); var conversation = UUID.randomUUID(); var run = UUID.randomUUID();
        var finished = new AtomicBoolean(false);
        var secondRead = new CountDownLatch(1);
        var event = new TaskRunPort.RunEventView(run, null, "ASSISTANT_TURN", 1,
                "MODEL_REQUEST", null, "n=1", Instant.parse("2026-10-01T00:00:00Z"));
        when(query.read(project, conversation, "action-1")).thenAnswer(call -> {
            secondRead.countDown();
            return new ExecutionEventQuery.Snapshot(run, finished.get() ? "RESULT_RECORDED" : "CLAIMED",
                    finished.get(), null, finished.get(), List.of(event));
        });
        try (var streams = new ClosableStream(query)) {
            var mvc = MockMvcBuilders.standaloneSetup(new ExecutionEventController(streams.value, null)).build();
            var request = mvc.perform(get("/api/writing/v2/projects/{p}/execution-events", project)
                    .param("conversationId", conversation.toString()).param("clientActionId", "action-1"))
                    .andExpect(request().asyncStarted()).andReturn();
            assertTrue(secondRead.await(2, TimeUnit.SECONDS));
            // Wait for the next poll: the first event has been flushed but the root is still running.
            Thread.sleep(650);
            String live = request.getResponse().getContentAsString();
            assertTrue(live.contains("event:execution-event"));
            assertTrue(live.contains("MODEL_REQUEST"));
            assertFalse(live.contains("execution-end"));
            finished.set(true);
            request.getAsyncResult(3000);
            mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
            String body = request.getResponse().getContentAsString();
            assertEquals(1, body.split("event:execution-event", -1).length - 1);
            assertTrue(body.contains("event:execution-end"));

            String cursor = ExecutionEventStream.encodeCursor(Map.of(run.toString(), 1L));
            var replay = mvc.perform(get("/api/writing/v2/projects/{p}/execution-events", project)
                    .param("conversationId", conversation.toString()).param("clientActionId", "action-1")
                    .header("Last-Event-ID", cursor)).andReturn();
            replay.getAsyncResult(3000);
            mvc.perform(asyncDispatch(replay)).andExpect(status().isOk());
            assertFalse(replay.getResponse().getContentAsString().contains("event:execution-event"));
            assertTrue(replay.getResponse().getContentAsString().contains("execution-end"));
        }
    }

    @Test
    void rejectsInvalidCursorAndDoesNotTreatUnknownAsSuccess() throws Exception {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> ExecutionEventStream.decodeCursor("not-json"));
        var query = mock(ExecutionEventQuery.class);
        var p = UUID.randomUUID(); var c = UUID.randomUUID();
        when(query.read(p, c, "unknown")).thenReturn(new ExecutionEventQuery.Snapshot(
                UUID.randomUUID(), "UNKNOWN_GENERATION", true, "UNKNOWN", true, List.of()));
        try (var streams = new ClosableStream(query)) {
            var mvc = MockMvcBuilders.standaloneSetup(new ExecutionEventController(streams.value, null)).build();
            var request = mvc.perform(get("/api/writing/v2/projects/{p}/execution-events", p)
                    .param("conversationId", c.toString()).param("clientActionId", "unknown")).andReturn();
            request.getAsyncResult(3000);
            mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
            assertTrue(request.getResponse().getContentAsString().contains("UNKNOWN_GENERATION"));
            assertFalse(request.getResponse().getContentAsString().contains("RESULT_RECORDED"));
        }
    }

    @Test
    void preservesSourceSequenceEvenWhenTransactionTimestampsAreReversed() {
        var run = UUID.randomUUID();
        var first = new TaskRunPort.RunEventView(run, null, "ASSISTANT_TURN", 1,
                "MODEL_REQUEST", null, "first", Instant.parse("2026-10-01T00:00:02Z"));
        var second = new TaskRunPort.RunEventView(run, null, "ASSISTANT_TURN", 2,
                "TOOL_REQUEST", null, "second", Instant.parse("2026-10-01T00:00:01Z"));
        assertEquals(List.of(first, second), ExecutionEventStream.deliveryOrder(List.of(second, first)));
    }

    private static class ClosableStream implements AutoCloseable {
        final ExecutionEventStream value;
        ClosableStream(ExecutionEventQuery query) { value = new ExecutionEventStream(query); }
        public void close() { value.close(); }
    }
}

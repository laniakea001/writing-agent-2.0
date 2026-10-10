package com.writingagent.v2.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.writingagent.v2.application.port.TaskRunPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveTracePersistenceTest {
    @Test
    void snapshotReplayDoesNotCountLiveModelRequestTwice() {
        var port = mock(TaskRunPort.class); var run = UUID.randomUUID();
        when(port.findTraceSequence(run, "main-trace-1")).thenReturn(null, 7L);
        when(port.appendEvent(run, "MODEL_REQUEST", null, "main-trace-1", "n=1")).thenReturn(7L);
        var service = new TaskRunService(port);
        assertEquals(7, service.appendTrace(run, "main-trace-1", "MODEL_REQUEST", null, "n=1"));
        assertEquals(7, service.appendTrace(run, "main-trace-1", "MODEL_REQUEST", null, "n=1"));
        verify(port, times(1)).appendEvent(run, "MODEL_REQUEST", null, "main-trace-1", "n=1");
    }
}

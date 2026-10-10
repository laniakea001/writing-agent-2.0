package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.writingagent.v2.agent.LiveTraceList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveTraceListTest {
    @Test
    void observerSeesEventsImmediatelyAndSnapshotRetainsThem() {
        var observed = new ArrayList<String>(); var trace = new LiveTraceList<String>(observed::add);
        trace.add("MODEL_REQUEST");
        assertEquals(List.of("MODEL_REQUEST"), observed);
        trace.add("TOOL_REQUEST");
        assertEquals(List.of("MODEL_REQUEST", "TOOL_REQUEST"), List.copyOf(trace));
    }
}

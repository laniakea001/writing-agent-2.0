package com.writingagent.v2.agent.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolInvocationLedgerTest {

    @Test
    void perInstanceCountersDoNotShareState() {
        ToolInvocationLedger a = new ToolInvocationLedger();
        ToolInvocationLedger b = new ToolInvocationLedger();
        a.recordSuccess("p1", "{\"projectId\":\"p1\",\"ctxMark\":\"CTX-MARK-aaaa\"}");
        assertTrue(a.executed());
        assertEquals(1, a.invocationCount());
        assertFalse(b.executed());
        assertEquals(0, b.invocationCount());
        assertNotEquals("", a.lastResultSha256());
    }
}

package com.writingagent.v2.agent.main;

import static org.junit.jupiter.api.Assertions.*;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolCallIdentityTest {
    @Test
    void missingCallIdRejectsSideEffectBeforeApplicationIsReached_R328() {
        var context=new MainAgentTurnContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"local");
        context.bindUserMessage("请生成计划");
        // Null dependency is deliberate: missing identity must fail before reaching generation.
        var tool=new ProposeWritingPlanTool(context,null);
        RuntimeException rejected=assertThrows(RuntimeException.class,
                () -> tool.callAsync(ToolCallParam.builder().input(Map.of()).build()).block());
        assertEquals("TOOL_CALL_ID_MISSING",rejected.getMessage());
        assertTrue(context.receipts().isEmpty());
    }
}

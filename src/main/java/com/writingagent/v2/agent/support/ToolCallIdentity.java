package com.writingagent.v2.agent.support;

import io.agentscope.core.tool.ToolCallParam;
import com.writingagent.v2.runtime.RunContractException;

/** Framework identity is not a value that a write Tool may synthesize. */
public final class ToolCallIdentity {
    private ToolCallIdentity() {}
    public static String require(ToolCallParam param) {
        String id=param==null || param.getToolUseBlock()==null ? null : param.getToolUseBlock().getId();
        if(id==null || id.isBlank()) throw new RunContractException("TOOL_CALL_ID_MISSING");
        return id;
    }
}

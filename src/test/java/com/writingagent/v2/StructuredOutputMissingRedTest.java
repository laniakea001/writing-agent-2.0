package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.writingagent.v2.agent.plan.PlanAgentRuntime;
import com.writingagent.v2.agent.plan.StructuredOutputMissingException;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.WorkbenchTurnService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Zero-model red proof: STRUCTURED_OUTPUT_MISSING classification, honest count/trace, no second
 * agent call. Does <b>not</b> TRUNCATE business tables.
 */
@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class StructuredOutputMissingRedTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        com.writingagent.v2.support.TestDatabase.register(registry);
        registry.add("server.port", () -> "0");
    }

    @MockitoBean PlanAgentRuntime planAgent;

    @Autowired WorkbenchTurnService turns;
    @Autowired ProjectApplicationService projects;
    @Autowired JdbcTemplate jdbc;

    @Test
    void structuredOutputMissing_persistsHonestCountAndRefreshVisibleFailure() {
        when(planAgent.run(any(), any(), anyString(), anyString(), any(), any()))
                .thenThrow(
                        new StructuredOutputMissingException(
                                "No structured output on agent result Msg",
                                2,
                                List.of(
                                        new PlanAgentRuntime.TraceLine(1, "MODEL_REQUEST", "n=1"),
                                        new PlanAgentRuntime.TraceLine(
                                                2, "TOOL_REQUEST", "read_current_project_context"),
                                        new PlanAgentRuntime.TraceLine(3, "MODEL_REQUEST", "n=2"),
                                        new PlanAgentRuntime.TraceLine(
                                                4, "STRUCTURED_OUTPUT_MISSING", "hasStructuredData=false"))));

        var ws = projects.createWorkspace("A2-R1-red", "local-fixture");
        var proj =
                projects.createProject(
                        (UUID) ws.get("workspaceId"),
                        "A2-R1 red project",
                        "red-test goal: no model");
        UUID projectId = (UUID) proj.get("projectId");
        UUID conversationId = (UUID) proj.get("conversationId");
        String clientActionId = "red-" + UUID.randomUUID();
        String userMsg = "RED proof user message — expect STRUCTURED_OUTPUT_MISSING";

        Map<String, Object> result =
                turns.generateOrContinue(projectId, conversationId, clientActionId, userMsg);

        assertEquals("STRUCTURED_OUTPUT_MISSING", result.get("code"));
        assertEquals(502, ((Number) result.get("httpStatusHint")).intValue());
        assertEquals(2, ((Number) result.get("modelRequestCount")).intValue());
        assertFalse("MODEL_UNAVAILABLE".equals(result.get("code")));

        verify(planAgent, times(1)).run(any(), any(), anyString(), anyString(), any(), any());
        verify(planAgent, Mockito.atMost(1)).run(any(), any(), anyString(), anyString(), any(), any());

        UUID actionId = (UUID) result.get("actionId");
        Integer runCount =
                jdbc.queryForObject(
                        """
                        SELECT r.model_request_count
                        FROM writing_v2.agent_run r
                        WHERE r.action_id = ?
                        """,
                        Integer.class,
                        actionId);
        assertEquals(2, runCount);

        String errCode =
                jdbc.queryForObject(
                        """
                        SELECT error_code FROM writing_v2.action_ledger WHERE action_id = ?
                        """,
                        String.class,
                        actionId);
        assertEquals("STRUCTURED_OUTPUT_MISSING", errCode);

        Integer traceRows =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM writing_v2.run_event e
                        JOIN writing_v2.agent_run r ON r.run_id = e.run_id
                        WHERE r.action_id = ? AND e.raw_event_type IN ('MODEL_REQUEST','TOOL_REQUEST','STRUCTURED_OUTPUT_MISSING')
                        """,
                        Integer.class,
                        actionId);
        assertTrue(traceRows != null && traceRows >= 3, "traceRows=" + traceRows);

        Map<String, Object> wb = turns.workbench(projectId, conversationId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) wb.get("messages");
        assertTrue(messages.stream().anyMatch(m -> userMsg.equals(m.get("body"))));
        assertTrue(
                messages.stream()
                        .anyMatch(
                                m ->
                                        "assistant".equals(m.get("role"))
                                                && String.valueOf(m.get("body"))
                                                        .contains("STRUCTURED_OUTPUT_MISSING")));
        assertTrue(wb.containsKey("lastFailure"));
    }
}

package com.writingagent.v2.application;

import com.writingagent.v2.application.generation.GenerationCommand;
import com.writingagent.v2.application.generation.MessagePolicy;
import com.writingagent.v2.application.generation.PlanGenerationModule;
import com.writingagent.v2.application.port.ConversationPort;
import com.writingagent.v2.application.port.PlanRevisionPort;
import com.writingagent.v2.application.port.ProjectPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Workbench button entry for plan turns. Product assistant chat after shared module; user message is
 * recorded inside {@link PlanGenerationModule} when {@link MessagePolicy#workbenchButton()}.
 */
@Service
public class WorkbenchTurnService {

    public static final String OP = "GENERATE_OR_CONTINUE_PLAN";
    public static final String CODE_STRUCTURED_OUTPUT_MISSING = "STRUCTURED_OUTPUT_MISSING";
    public static final String CODE_MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    public static final String CODE_MODEL_OUTPUT_INVALID = "MODEL_OUTPUT_INVALID";

    private final ProjectApplicationService projects;
    private final PlanGenerationModule planGeneration;
    private final TransactionTemplate tx;
    private final PlanRevisionPort plans;
    private final ConversationPort conversations;

    public WorkbenchTurnService(
            ProjectApplicationService projects,
            PlanGenerationModule planGeneration,
            TransactionTemplate tx,
            PlanRevisionPort plans,
            ConversationPort conversations) {
        this.projects = projects;
        this.planGeneration = planGeneration;
        this.tx = tx;
        this.plans = plans;
        this.conversations = conversations;
    }

    public Map<String, Object> generateOrContinue(
            UUID projectId, UUID conversationId, String clientActionId, String message) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId required");
        }
        String text = message == null ? "" : message;

        Map<String, Object> result =
                planGeneration.execute(
                        GenerationCommand.plan(
                                projectId,
                                conversationId,
                                clientActionId,
                                text,
                                MessagePolicy.workbenchButton(),
                                null));

        if ("REPLAY".equals(String.valueOf(result.get("outcome")))) {
            return result;
        }
        if (result.containsKey("code") && result.get("httpStatusHint") != null) {
            int http = ((Number) result.get("httpStatusHint")).intValue();
            if (http >= 400) {
                tx.executeWithoutResult(
                        s ->
                                conversations.insertAssistantMessage(
                                        UUID.randomUUID(),
                                        conversationId,
                                        failureBodyJson(
                                                String.valueOf(result.get("code")),
                                                (UUID) result.get("actionId"),
                                                ((Number)
                                                                result.getOrDefault(
                                                                        "modelRequestCount", 0))
                                                        .intValue(),
                                                String.valueOf(
                                                        result.getOrDefault("failureDetail", "")))));
                return result;
            }
        }

        String outcome = String.valueOf(result.get("outcome"));
        if (!"NEEDS_INPUT".equals(outcome)) {
            tx.executeWithoutResult(
                    s ->
                            conversations.insertAssistantMessage(
                                    UUID.randomUUID(),
                                    conversationId,
                                    successBodyJson(
                                            outcome,
                                            (UUID) result.get("planRevisionId"),
                                            (UUID) result.get("actionId"),
                                            ((Number) result.getOrDefault("modelRequestCount", 0))
                                                    .intValue())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> workbench(UUID projectId, UUID conversationId) {
        ProjectPort.ProjectView project = projects.getProject(projectId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("title", project.title());
        body.put("projectVersion", project.projectVersion());
        body.put("currentPlanRevisionId", project.currentPlanRevisionId());
        body.put("researchGoal", projects.currentResearchGoal(projectId));
        body.put("conversationId", conversationId);
        if (project.currentPlanRevisionId() != null) {
            PlanRevisionPort.PlanRevisionView plan = plans.findById(project.currentPlanRevisionId());
            body.put("planStatus", plan.status());
            body.put("planRevisionNo", plan.revisionNo());
            body.put("planPayload", plan.payloadJson());
            body.put("contentHash", plan.contentHash());
            body.put("confirmedAt", plan.confirmedAt());
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        for (ConversationPort.MessageView m : conversations.listMessages(conversationId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("messageId", m.messageId());
            row.put("role", m.role());
            row.put("body", m.body());
            row.put("createdAt", m.createdAt().toString());
            row.put("sourceRunId", m.sourceRunId());
            messages.add(row);
        }
        body.put("messages", messages);

        Map<String, Object> lastFailure = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if (!"assistant".equals(m.get("role"))) {
                continue;
            }
            String b = String.valueOf(m.get("body"));
            if (b.contains("\"kind\":\"TURN_FAILED\"") || b.contains("\"kind\": \"TURN_FAILED\"")) {
                lastFailure = Map.of("body", b, "createdAt", m.get("createdAt"));
                break;
            }
        }
        if (lastFailure != null) {
            body.put("lastFailure", lastFailure);
        }
        return body;
    }

    private static String failureBodyJson(
            String code, UUID actionId, int modelRequestCount, String detail) {
        String d =
                detail == null
                        ? ""
                        : detail.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        if (d.length() > 500) {
            d = d.substring(0, 500);
        }
        return "{\"kind\":\"TURN_FAILED\",\"code\":\""
                + code
                + "\",\"actionId\":\""
                + actionId
                + "\",\"modelRequestCount\":"
                + modelRequestCount
                + ",\"summary\":\""
                + d
                + "\"}";
    }

    private static String successBodyJson(
            String outcome, UUID planRevisionId, UUID actionId, int modelRequestCount) {
        return "{\"kind\":\"TURN_RESULT\",\"outcome\":\""
                + (outcome == null ? "" : outcome)
                + "\",\"planRevisionId\":"
                + (planRevisionId == null ? "null" : "\"" + planRevisionId + "\"")
                + ",\"actionId\":\""
                + actionId
                + "\",\"modelRequestCount\":"
                + modelRequestCount
                + "}";
    }
}

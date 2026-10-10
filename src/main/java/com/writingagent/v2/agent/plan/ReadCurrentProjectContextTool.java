package com.writingagent.v2.agent.plan;

import com.writingagent.v2.agent.support.ToolInvocationLedger;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.port.ProjectPort;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.UUID;
import java.util.function.Consumer;

/** Server-scoped read-only tool. No write side effects. Per-invocation ledger (not static). */
public class ReadCurrentProjectContextTool {

    public static final String TOOL_NAME = "read_current_project_context";
    public static final String CTX_MARK_PREFIX = "CTX-MARK-";

    private final UUID projectId;
    private final UUID actionId;
    private final String ctxMark;
    private final ProjectApplicationService projects;
    private final ToolInvocationLedger ledger = new ToolInvocationLedger();
    /** Fired synchronously when Tool returns (before next model step). */
    private volatile Consumer<String> onToolResultDetail;

    public ReadCurrentProjectContextTool(
            UUID projectId, UUID actionId, ProjectApplicationService projects) {
        this.projectId = projectId;
        this.actionId = actionId;
        this.ctxMark = CTX_MARK_PREFIX + actionId.toString().substring(0, 8);
        this.projects = projects;
    }

    public ToolInvocationLedger ledger() {
        return ledger;
    }

    public void setOnToolResultDetail(Consumer<String> onToolResultDetail) {
        this.onToolResultDetail = onToolResultDetail;
    }

    /** @deprecated use {@link #ledger()}{@invocationCount()} — kept for CP0/log compatibility. */
    @Deprecated
    public static int executorInvocations() {
        return -1;
    }

    @Deprecated
    public static void resetExecutorInvocations() {
        // no-op: counters are per-instance
    }

    public String ctxMark() {
        return ctxMark;
    }

    public UUID boundProjectId() {
        return projectId;
    }

    @Tool(
            name = TOOL_NAME,
            description =
                    "Read current project context for the server-bound project."
                            + " Copy ctxMark into PlanOutcome.contextMarkEcho.")
    public String readCurrentProjectContext(
            @ToolParam(name = "reason", description = "Why context is needed", required = false)
                    String reason) {
        ProjectPort.ProjectView project = projects.getProject(projectId);
        String goal = projects.currentResearchGoal(projectId);
        UUID currentPlan = project.currentPlanRevisionId();
        String json =
                """
                {"projectId":"%s","actionId":"%s","title":"%s","researchGoal":%s,"currentPlanRevisionId":%s,"ctxMark":"%s","documentsIndexed":0,"note":"No project retrieval; evidenceBasis must be USER_GOAL_AND_MODEL_SYNTHESIS"}
                """
                        .formatted(
                                projectId,
                                actionId,
                                escape(String.valueOf(project.title())),
                                jsonString(goal),
                                currentPlan == null ? "null" : jsonString(currentPlan.toString()),
                                ctxMark);
        ledger.recordSuccess(projectId.toString(), json);
        Consumer<String> sink = onToolResultDetail;
        if (sink != null) {
            sink.accept(
                    "sha256="
                            + ledger.lastResultSha256()
                            + " projectId="
                            + ledger.boundProjectId()
                            + " invocations="
                            + ledger.invocationCount()
                            + " when=tool_return");
        }
        return json;
    }

    private static String jsonString(String s) {
        return "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

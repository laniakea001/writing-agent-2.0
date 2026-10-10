package com.writingagent.v2.infrastructure.jdbc;

import com.writingagent.v2.application.port.ProjectPort;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectAdapter implements ProjectPort {

    private final JdbcTemplate jdbc;

    public JdbcProjectAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertWorkspace(UUID workspaceId, String displayName, String ownerScope) {
        jdbc.update(
                """
                INSERT INTO writing_v2.workspace(workspace_id, display_name, owner_scope)
                VALUES (?, ?, ?)
                """,
                workspaceId,
                displayName,
                ownerScope);
    }

    @Override
    public void insertProject(UUID projectId, UUID workspaceId, String title) {
        jdbc.update(
                """
                INSERT INTO writing_v2.writing_project(project_id, workspace_id, title, project_version)
                VALUES (?, ?, ?, 1)
                """,
                projectId,
                workspaceId,
                title);
    }

    @Override
    public void insertSpecification(UUID specRevisionId, UUID projectId, String researchGoal) {
        jdbc.update(
                """
                INSERT INTO writing_v2.specification_revision(
                  spec_revision_id, project_id, revision_no, research_goal)
                VALUES (?, ?, 1, ?)
                """,
                specRevisionId,
                projectId,
                researchGoal);
    }

    @Override
    public void insertConversation(UUID conversationId, UUID projectId) {
        jdbc.update(
                """
                INSERT INTO writing_v2.conversation(conversation_id, project_id, workspace_id)
                SELECT ?,project_id,workspace_id FROM writing_v2.writing_project WHERE project_id=?
                """,
                conversationId,
                projectId);
        jdbc.update("INSERT INTO writing_v2.conversation_run_admission(conversation_id) VALUES(?)",conversationId);
    }

    @Override
    public void insertUserMessage(UUID messageId, UUID conversationId, String body) {
        jdbc.update(
                """
                INSERT INTO writing_v2.conversation_message(message_id, conversation_id, role, body)
                VALUES (?, ?, 'user', ?)
                """,
                messageId,
                conversationId,
                body);
    }

    @Override
    public ProjectView getProject(UUID projectId) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        """
                        SELECT title, project_version, current_plan_revision_id
                        FROM writing_v2.writing_project WHERE project_id = ?
                        """,
                        projectId);
        return new ProjectView(
                (String) row.get("title"),
                ((Number) row.get("project_version")).longValue(),
                (UUID) row.get("current_plan_revision_id"));
    }

    @Override
    public String currentResearchGoal(UUID projectId) {
        return jdbc.queryForObject(
                """
                SELECT research_goal FROM writing_v2.specification_revision
                WHERE project_id = ? ORDER BY revision_no DESC LIMIT 1
                """,
                String.class,
                projectId);
    }

    @Override
    public UUID currentPlanRevisionId(UUID projectId) {
        return jdbc.queryForObject(
                "SELECT current_plan_revision_id FROM writing_v2.writing_project WHERE project_id = ?",
                UUID.class,
                projectId);
    }

    @Override
    public void setCurrentPlanRevision(UUID projectId, UUID planRevisionId) {
        jdbc.update(
                """
                UPDATE writing_v2.writing_project
                SET current_plan_revision_id = ?, project_version = project_version + 1, updated_at = NOW()
                WHERE project_id = ?
                """,
                planRevisionId,
                projectId);
    }
}

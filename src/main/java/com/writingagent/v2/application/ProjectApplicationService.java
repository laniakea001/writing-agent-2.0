package com.writingagent.v2.application;

import com.writingagent.v2.application.port.ProjectPort;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectApplicationService {

    private final ProjectPort projects;

    public ProjectApplicationService(ProjectPort projects) {
        this.projects = projects;
    }

    @Transactional
    public Map<String, Object> createWorkspace(String displayName, String ownerScope) {
        UUID workspaceId = UUID.randomUUID();
        projects.insertWorkspace(workspaceId, displayName, ownerScope);
        return Map.of("workspaceId", workspaceId, "ownerScope", ownerScope);
    }

    @Transactional
    public Map<String, Object> createProject(UUID workspaceId, String title, String researchGoal) {
        UUID projectId = UUID.randomUUID();
        projects.insertProject(projectId, workspaceId, title);
        UUID specId = UUID.randomUUID();
        projects.insertSpecification(specId, projectId, researchGoal);
        UUID conversationId = UUID.randomUUID();
        projects.insertConversation(conversationId, projectId);
        projects.insertUserMessage(UUID.randomUUID(), conversationId, researchGoal);
        return Map.of(
                "projectId",
                projectId,
                "projectVersion",
                1L,
                "conversationId",
                conversationId,
                "specRevisionId",
                specId);
    }

    public ProjectPort.ProjectView getProject(UUID projectId) {
        return projects.getProject(projectId);
    }

    public String currentResearchGoal(UUID projectId) {
        return projects.currentResearchGoal(projectId);
    }
}

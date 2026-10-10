package com.writingagent.v2.application.port;

import java.util.UUID;

/** Persistence port for workspace / project / specification / conversation bootstrap. */
public interface ProjectPort {

    /** Typed project row for application / tool consumers. */
    record ProjectView(String title, long projectVersion, UUID currentPlanRevisionId) {}

    void insertWorkspace(UUID workspaceId, String displayName, String ownerScope);

    void insertProject(UUID projectId, UUID workspaceId, String title);

    void insertSpecification(UUID specRevisionId, UUID projectId, String researchGoal);

    void insertConversation(UUID conversationId, UUID projectId);

    void insertUserMessage(UUID messageId, UUID conversationId, String body);

    ProjectView getProject(UUID projectId);

    String currentResearchGoal(UUID projectId);

    UUID currentPlanRevisionId(UUID projectId);

    void setCurrentPlanRevision(UUID projectId, UUID planRevisionId);
}

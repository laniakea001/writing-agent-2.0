package com.writingagent.v2.application.capability;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Commands shared by HTTP and trusted Tool adapters, not model-specific output DTOs. */
public final class WritingCommands {
    private WritingCommands() {}
    public record PlanSave(String clientActionId, Long expectedProjectVersion, UUID expectedPlanRevisionId, JsonNode payload) {}
    public record ChapterSave(String clientActionId, UUID expectedPlanRevisionId, UUID expectedChapterRevisionId, Long expectedArticleVersion, JsonNode contentJson) {}
    public record MetadataSave(String clientActionId, Long expectedArticleVersion, UUID expectedMetadataRevisionId, JsonNode payload) {}
    public record ProposalPrepare(String clientActionId, String type, String origin, UUID targetPlanRevisionId, Long expectedProjectVersion, Long expectedArticleVersion) {}
    public record ProposalDecision(String clientActionId, String decision) {}
}

package com.writingagent.v2.agent.main;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.writingagent.v2.application.*;
import com.writingagent.v2.application.generation.*;
import com.writingagent.v2.application.port.*;
import com.writingagent.v2.agent.chapter.ChapterAgentRuntime;
import com.writingagent.v2.domain.ActionStatus;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WritingToolSafetyTest {
    @Test void currentProjectSnapshotReflectsConfirmationWithoutReusingConversationMemory() {
        UUID project=UUID.randomUUID(),plan=UUID.randomUUID();
        var projects=mock(ProjectApplicationService.class);
        when(projects.getProject(project)).thenReturn(new ProjectPort.ProjectView("title",1,plan));
        when(projects.currentResearchGoal(project)).thenReturn("goal");
        var plans=mock(PlanRevisionPort.class);
        when(plans.findById(plan)).thenReturn(new PlanRevisionPort.PlanRevisionView("DRAFT",1,"{}","hash",null),
                new PlanRevisionPort.PlanRevisionView("CONFIRMED",1,"{}","hash",java.time.Instant.now()));
        var context=new MainAgentTurnContext(project,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"local");
        var tool=new ReadWritingProjectTool(context,projects,plans,mock(ArticlePort.class));
        assertEquals(false,tool.snapshot().get("planConfirmed"));
        assertEquals(true,tool.snapshot().get("planConfirmed"));
    }
    @Test void realProposeToolRejectsChapterRequestBeforeCallingGeneration() {
        var context = new MainAgentTurnContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"local");
        context.bindUserMessage("只重写第1章，末尾加入 SSE_MY_CHECK_1003，不改第2章");
        var generation = mock(PlanGenerationModule.class);
        var block = new ProposeWritingPlanTool(context,generation)
                .callAsync(ToolCallParam.builder().input(Map.of("userIntent","new plan"))
                        .toolUseBlock(io.agentscope.core.message.ToolUseBlock.builder()
                                .id("actual-test-call").name("propose_writing_plan").input(Map.of()).build())
                        .build()).block();
        assertNotNull(block);
        assertEquals("PLAN_REPLACEMENT_NOT_REQUESTED",context.receipts().get(0).get("code"));
        verifyNoInteractions(generation);
    }
    @Test void realRewriteUseCaseSettlesUnconfirmedFailureRatherThanLeavingClaimed() {
        UUID project=UUID.randomUUID(),conversation=UUID.randomUUID(),action=UUID.randomUUID();
        var actions=mock(ActionLedgerService.class);
        when(actions.claim(eq(project),eq("test"),anyString(),eq("REWRITE_CHAPTER")))
                .thenReturn(new ActionLedgerService.ClaimResult(ActionLedgerService.ClaimKind.WINNER,action,ActionStatus.CLAIMED,"digest",null));
        var articles=mock(ArticleApplicationService.class);
        when(articles.confirmedChapterOutline(project)).thenThrow(new ArticleApplicationService.PlanNotConfirmedException("draft"));
        var runtime=mock(ChapterAgentRuntime.class);
        var service=new ArticleChapterTurnService(mock(ProjectApplicationService.class),actions,mock(TaskRunService.class),
                articles,mock(ArticlePort.class),runtime,mock(ConversationPort.class),mock(org.springframework.transaction.support.TransactionTemplate.class));
        var result=service.rewriteChapter(project,conversation,"test",1,"重写",MessagePolicy.mainAgentTool());
        assertEquals("PLAN_NOT_CONFIRMED",result.get("code"));
        verify(actions).markResult(action,ActionStatus.FAILED,null,false,"PLAN_NOT_CONFIRMED");
        verifyNoInteractions(runtime);
    }
}

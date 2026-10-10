package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.capability.WritingCapability;
import com.writingagent.v2.application.capability.WritingCommands;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class GeneratedWritingCapabilityTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",com.writingagent.v2.support.TestDatabase::url);
        r.add("spring.flyway.clean-disabled",()->true);
    }
    @Autowired ProjectApplicationService projects;
    @Autowired WritingCapability writing;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Test void invalidGeneratedPlanInsideArtifactTransactionRetainsActualRejection() throws Exception {
        UUID ws=(UUID)projects.createWorkspace("事务反例","local-fixture").get("workspaceId");
        UUID id=(UUID)projects.createProject(ws,"错误不能被领取状态掩盖","反例").get("projectId");
        var command=new WritingCommands.PlanSave("invalid-generated-plan",1L,null,new ObjectMapper().readTree("{\"chapters\":[]}"));
        var failure=assertThrows(com.writingagent.v2.domain.WritingRuleException.class,()->tx.execute(s->writing.saveGeneratedPlan(id,command)));
        assertEquals("PLAN_SCHEMA_INVALID",failure.code());
        assertNull(writing.workbench(id).get("plan"));
    }
    @Test void generatedPlanIsDraftWithHonestBasisAndSameReplayReceipt() throws Exception {
        UUID ws=(UUID)projects.createWorkspace("生成能力验收","local-fixture").get("workspaceId");
        UUID id=(UUID)projects.createProject(ws,"生成计划能力","两章").get("projectId");
        var command=new WritingCommands.PlanSave("generated-plan",1L,null,new ObjectMapper().readTree("{\"chapters\":[{\"title\":\"定义\"},{\"title\":\"边界\"}]}"));
        var receipt=writing.saveGeneratedPlan(id,command);
        var plan=writing.plan(id,UUID.fromString(receipt.get("planRevisionId").toString()));
        assertEquals("DRAFT",plan.get("status"));
        assertEquals("USER_GOAL_AND_MODEL_SYNTHESIS",plan.get("sourceBasis"));
        assertEquals(receipt.get("recordedAt"),writing.saveGeneratedPlan(id,command).get("recordedAt"));
        assertNull(writing.workbench(id).get("article"));
    }
}

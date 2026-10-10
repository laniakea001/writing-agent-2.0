package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.writingagent.v2.application.ProjectApplicationService;
import com.writingagent.v2.application.capability.StageAiRuns;
import com.writingagent.v2.domain.WritingRuleException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="writing.batch.dispatch-enabled=false")
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class StageAiAdmissionTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",com.writingagent.v2.support.TestDatabase::url);}
    @Autowired ProjectApplicationService projects;
    @Autowired StageAiRuns runs;
    @Autowired com.writingagent.v2.application.capability.WritingCapability writing;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Test void disabledDispatcherCannotAcceptAnIndefinitelyWaitingBatch() throws Exception {
        UUID ws=(UUID)projects.createWorkspace("关闭调度准入反例","local-fixture").get("workspaceId");
        UUID p=(UUID)projects.createProject(ws,"不可假等待","测试").get("projectId");
        var plan=writing.savePlan(p,new com.writingagent.v2.application.capability.WritingCommands.PlanSave("plan",1L,null,com.writingagent.v2.domain.DocumentJsonSupport.parse("{\"chapters\":[{\"title\":\"第一章\"}]}")));
        var card=writing.prepare(p,new com.writingagent.v2.application.capability.WritingCommands.ProposalPrepare("card","CONFIRM_PLAN","HTTP",(UUID)plan.get("planRevisionId"),2L,null));
        writing.decide((UUID)card.get("proposalId"),new com.writingagent.v2.application.capability.WritingCommands.ProposalDecision("yes","APPROVE"));
        var a=writing.article(p);var key=UUID.fromString(com.writingagent.v2.domain.DocumentJsonSupport.JSON.valueToTree(a).path("chapters").get(0).path("chapterKey").asText());
        assertEquals("BATCH_DISPATCH_UNAVAILABLE",assertThrows(WritingRuleException.class,()->runs.submitBatch(p,new com.writingagent.v2.application.capability.BatchRewriteWorkflow.Request("no-dispatch",java.util.List.of(key),"润色"))).code());
        var body=com.writingagent.v2.domain.DocumentJsonSupport.JSON.valueToTree(new com.writingagent.v2.application.capability.BatchRewriteWorkflow.Request("http-no-dispatch",java.util.List.of(key),"润色")).toString();
        var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/writing/v2/projects/"+p+"/batch-rewrites")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(409,response.statusCode(),response.body());assertEquals("BATCH_DISPATCH_UNAVAILABLE",com.writingagent.v2.domain.DocumentJsonSupport.parse(response.body()).path("code").asText());
    }
    @Test void unconfirmedChapterIsRejectedBeforeRunAcceptanceAndLookupDoesNotExecute(){
        UUID ws=(UUID)projects.createWorkspace("AI准入反例","local-fixture").get("workspaceId");
        UUID id=(UUID)projects.createProject(ws,"未确认不得写章","反例").get("projectId");
        assertEquals("PLAN_NOT_CONFIRMED",assertThrows(WritingRuleException.class,()->runs.submit(id,new StageAiRuns.Request("no-plan","GENERATE_CHAPTER","",UUID.randomUUID()))).code());
        assertTrue(runs.list(ws).isEmpty());
        assertEquals("ACTION_NOT_FOUND",assertThrows(WritingRuleException.class,()->runs.lookup(id,"no-plan")).code());
        assertTrue(runs.list(ws).isEmpty());
    }
}

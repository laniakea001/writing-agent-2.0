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

@SpringBootTest
@EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class StageAiAdmissionTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",com.writingagent.v2.support.TestDatabase::url);}
    @Autowired ProjectApplicationService projects;
    @Autowired StageAiRuns runs;
    @Test void unconfirmedChapterIsRejectedBeforeRunAcceptanceAndLookupDoesNotExecute(){
        UUID ws=(UUID)projects.createWorkspace("AI准入反例","local-fixture").get("workspaceId");
        UUID id=(UUID)projects.createProject(ws,"未确认不得写章","反例").get("projectId");
        assertEquals("PLAN_NOT_CONFIRMED",assertThrows(WritingRuleException.class,()->runs.submit(id,new StageAiRuns.Request("no-plan","GENERATE_CHAPTER","",UUID.randomUUID()))).code());
        assertTrue(runs.list(ws).isEmpty());
        assertEquals("ACTION_NOT_FOUND",assertThrows(WritingRuleException.class,()->runs.lookup(id,"no-plan")).code());
        assertTrue(runs.list(ws).isEmpty());
    }
}

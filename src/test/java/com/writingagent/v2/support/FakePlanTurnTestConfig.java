package com.writingagent.v2.support;

import com.writingagent.v2.application.ActionLedgerService;
import com.writingagent.v2.application.PlanPersistenceUseCase;
import com.writingagent.v2.application.TaskRunService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class FakePlanTurnTestConfig {

    @Bean
    FakePlanTurnOrchestrator fakePlanTurnOrchestrator(
            ActionLedgerService actions,
            TaskRunService taskRuns,
            PlanPersistenceUseCase persistence) {
        return new FakePlanTurnOrchestrator(actions, taskRuns, persistence);
    }
}

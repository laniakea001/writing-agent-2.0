package com.writingagent.v2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.writingagent.v2.support.FakePlanTurnOrchestrator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Production Spring Context must not register FakePlanTurnOrchestrator (no @Import of test Fake).
 */
@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="WA2_D2_IT",matches="true")
class ProductionContextHasNoFakeBeanTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        com.writingagent.v2.support.TestDatabase.register(registry);
    }

    @Autowired ApplicationContext ctx;

    @Test
    void noFakePlanTurnOrchestratorBean() {
        Map<String, FakePlanTurnOrchestrator> beans =
                ctx.getBeansOfType(FakePlanTurnOrchestrator.class);
        assertTrue(beans.isEmpty(), "Fake beans present: " + beans.keySet());
        String[] names = ctx.getBeanDefinitionNames();
        for (String name : names) {
            assertTrue(
                    !name.toLowerCase().contains("fakeplan"),
                    "unexpected fake-related bean: " + name);
        }
    }
}

package com.writingagent.v2.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Integration tests never inherit the application's database credentials. */
public final class TestDatabase {
    private TestDatabase() {}

    public static String url() {
        String configured = System.getenv("WRITING_TEST_DB_URL");
        String url = configured == null || configured.isBlank()
                ? "jdbc:postgresql://127.0.0.1:5433/writing_agent_test?currentSchema=writing_v2"
                : configured;
        if (!url.matches("jdbc:postgresql://[^/]+/writing_agent_test(?:\\?.*)?")) {
            throw new IllegalStateException("Integration tests require the separate writing_agent_test database");
        }
        return url;
    }

    public static void register(DynamicPropertyRegistry registry) {
        String password = System.getenv("WRITING_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("WRITING_TEST_DB_PASSWORD is required for integration tests");
        }
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", () -> {
            String user = System.getenv("WRITING_TEST_DB_USER");
            return user == null || user.isBlank() ? "writing_app" : user;
        });
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.clean-disabled", () -> true);
    }
}

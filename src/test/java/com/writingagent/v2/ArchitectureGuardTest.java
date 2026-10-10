package com.writingagent.v2;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.jdbc.core.JdbcTemplate;

@AnalyzeClasses(
        packages = "com.writingagent.v2",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

    @ArchTest
    static final ArchRule applicationMustNotConsumeSdkEvents = noClasses()
            .that().resideInAPackage("com.writingagent.v2.application..")
            .should().dependOnClassesThat().resideInAPackage("io.agentscope.core.event..")
            .because("native event mapping belongs to the common runtime observer, not business use cases");

    @ArchTest
    static final ArchRule applicationMustNotDependOnJdbcTemplate =
            noClasses()
                    .that()
                    .resideInAPackage("com.writingagent.v2.application..")
                    .should()
                    .dependOnClassesThat()
                    .areAssignableTo(JdbcTemplate.class)
                    .because("SQL must live in infrastructure JDBC adapters behind ports");

    @ArchTest
    static final ArchRule applicationMustNotDependOnInfrastructure =
            noClasses()
                    .that()
                    .resideInAPackage("com.writingagent.v2.application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.writingagent.v2.infrastructure..")
                    .because("Application must not reverse-depend on infrastructure adapters");
}

package com.contextflow.common.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;

class LocalConfigurationTest {
    @TempDir Path directory;

    @Test
    void propertiesImportActivatesProfileAndKeepsSecretsAndDatabaseDefaults() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(
                file,
                "SPRING_PROFILES_ACTIVE=config-check\nCF_CORE_PORT=18080\n"
                        + "CF_DB_PASSWORD=space\\u0020hash\\u0023quote\\u0022slash\\u005cvalue\n"
                        + "CF_DB_URL=jdbc:postgresql://127.0.0.1:25432/local\n");
        try (var context =
                application()
                        .run(
                                "--CF_LOCAL_ENV_IMPORT=optional:file:"
                                        + file.toUri().getPath()
                                        + "[.properties]")) {
            var env = context.getEnvironment();
            assertTrue(env.matchesProfiles("config-check"));
            assertEquals("18080", env.getProperty("server.port"));
            assertEquals(
                    "space hash#quote\"slash\\value",
                    env.getProperty("spring.datasource.password"));
            assertEquals(
                    env.getProperty("spring.datasource.password"),
                    env.getProperty("spring.flyway.password"));
            assertEquals(
                    "jdbc:postgresql://127.0.0.1:25432/local",
                    env.getProperty("spring.flyway.url"));
            assertEquals("validate", env.getProperty("spring.jpa.hibernate.ddl-auto"));
        }
    }

    @Test
    void missingLocalFileAllowsContainerEnvironmentAndExplicitPortOverride() {
        try (var context =
                application()
                        .run(
                                "--CF_LOCAL_ENV_IMPORT=optional:file:"
                                        + directory.resolve("absent.env").toUri().getPath()
                                        + "[.properties]",
                                "--CF_DB_PASSWORD=isolated-test",
                                "--CF_CORE_PORT=8080")) {
            assertEquals("8080", context.getEnvironment().getProperty("server.port"));
            assertEquals(
                    "isolated-test",
                    context.getEnvironment().getProperty("spring.datasource.password"));
            assertFalse(context.getEnvironment().matchesProfiles("config-check"));
        }
    }

    private SpringApplication application() {
        var application = new SpringApplication(Probe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        return application;
    }

    @Configuration(proxyBeanMethods = false)
    static class Probe {}
}

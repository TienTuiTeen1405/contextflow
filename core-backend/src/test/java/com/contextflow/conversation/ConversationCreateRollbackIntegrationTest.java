package com.contextflow.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.contextflow.ai.service.ContinuityEpochBootstrap;
import com.contextflow.identity.demo.LocalDemoIdentity;
import jakarta.servlet.ServletException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** PR-4A: Conversation, Main 1 and the continuity epoch are committed or rolled back together. */
@SpringBootTest(properties = "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties")
@AutoConfigureMockMvc
@ActiveProfiles("local-demo")
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class ConversationCreateRollbackIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.1-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", POSTGRES::getUsername);
        properties.add("spring.flyway.password", POSTGRES::getPassword);
    }

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean private ContinuityEpochBootstrap continuityEpochBootstrap;

    @Test
    void failedEpochBootstrapRollsBackConversationAndMainStage() {
        UUID projectId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.projects (id, owner_user_id, name) VALUES (?, ?, 'Dự án')",
                projectId,
                LocalDemoIdentity.USER_ID);
        when(continuityEpochBootstrap.initializeForMainStage(any(), any(), any()))
                .thenThrow(new IllegalStateException("epoch bootstrap failed"));

        assertThrows(
                ServletException.class,
                () ->
                        mockMvc.perform(
                                post("/api/v1/projects/{projectId}/conversations", projectId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"Sẽ bị rollback\"}")));

        verify(continuityEpochBootstrap).initializeForMainStage(eq(projectId), any(), any());
        assertEquals(0, countRows("contextflow.conversations"));
        assertEquals(0, countRows("contextflow.main_stages"));
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}

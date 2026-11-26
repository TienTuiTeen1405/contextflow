package com.contextflow.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.contextflow.ai.service.ContinuityEpochBootstrap;
import com.contextflow.common.api.ApiProblem;
import com.contextflow.common.api.ErrorCode;
import com.contextflow.identity.demo.LocalDemoIdentity;
import com.contextflow.identity.demo.LocalDemoUserSeeder;
import com.contextflow.identity.service.CurrentUserProvider;
import com.contextflow.knowledge.service.IndexGenerationBootstrap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        properties = "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties")
@ActiveProfiles("local-demo")
@Testcontainers
class LocalDemoFoundationIntegrationTest {
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

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LocalDemoUserSeeder seeder;
    @Autowired private CurrentUserProvider currentUserProvider;
    @Autowired private IndexGenerationBootstrap indexGenerationBootstrap;
    @Autowired private ContinuityEpochBootstrap continuityEpochBootstrap;

    @AfterEach
    void resetSecurityAndData() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM contextflow.projects");
        jdbcTemplate.update(
                """
                UPDATE contextflow.users
                SET display_name = ?, role = 'USER', status = 'ACTIVE'
                WHERE id = ?
                """,
                LocalDemoIdentity.DISPLAY_NAME,
                LocalDemoIdentity.USER_ID);
    }

    @Test
    void seederIsIdempotentAndDoesNotOverwriteUserEdits() {
        jdbcTemplate.update(
                "UPDATE contextflow.users SET display_name = 'Edited User' WHERE id = ?",
                LocalDemoIdentity.USER_ID);
        seeder.run(null);
        String displayName =
                jdbcTemplate.queryForObject(
                        "SELECT display_name FROM contextflow.users WHERE id = ?",
                        String.class,
                        LocalDemoIdentity.USER_ID);
        assertEquals("Edited User", displayName);
    }

    @Test
    void currentUserProviderResolvesActivePrincipalAndRejectsMissingOrDisabledAccount() {
        ApiProblem unauthenticated =
                assertThrows(ApiProblem.class, () -> currentUserProvider.getCurrentUserId());
        assertEquals(ErrorCode.UNAUTHENTICATED, unauthenticated.code());

        bindDemoPrincipal();
        assertEquals(LocalDemoIdentity.USER_ID, currentUserProvider.getCurrentUserId());

        jdbcTemplate.update(
                "UPDATE contextflow.users SET status = 'DISABLED' WHERE id = ?",
                LocalDemoIdentity.USER_ID);
        ApiProblem disabled =
                assertThrows(ApiProblem.class, () -> currentUserProvider.getCurrentUserId());
        assertEquals(ErrorCode.ACCOUNT_DISABLED, disabled.code());
        assertThrows(IllegalStateException.class, () -> seeder.run(null));
    }

    @Test
    void bootstrapHelpersInitializeIndexGenerationAndContinuityEpoch() {
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID mainStageId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.projects (id, owner_user_id, name) VALUES (?, ?, ?)",
                projectId,
                LocalDemoIdentity.USER_ID,
                "Foundation Project");
        jdbcTemplate.update(
                "INSERT INTO contextflow.conversations (id, project_id, title) VALUES (?, ?, ?)",
                conversationId,
                projectId,
                "Foundation Conversation");
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.main_stages
                    (id, project_id, conversation_id, stage_order, title)
                VALUES (?, ?, ?, 1, 'Main 1')
                """,
                mainStageId,
                projectId,
                conversationId);

        UUID generationId = indexGenerationBootstrap.initializeForProject(projectId);
        UUID epochId =
                continuityEpochBootstrap.initializeForMainStage(
                        projectId, conversationId, mainStageId);

        Long generationCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM contextflow.index_generations
                        WHERE id = ? AND project_id = ?
                            AND embedding_model = 'gemini-embedding-2'
                            AND output_dimensionality = 1536
                            AND status = 'ACTIVE'
                        """,
                        Long.class,
                        generationId,
                        projectId);
        Long epochCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM contextflow.continuity_epochs
                        WHERE id = ? AND project_id = ? AND conversation_id = ?
                            AND main_stage_id = ? AND route_kind = 'MAIN' AND route_id = ?
                            AND epoch_no = 1 AND status = 'ACTIVE'
                            AND provider = 'MOCK' AND api_interface = 'INTERACTIONS'
                            AND octet_length(compatibility_key) = 32
                        """,
                        Long.class,
                        epochId,
                        projectId,
                        conversationId,
                        mainStageId,
                        mainStageId);
        assertEquals(1L, generationCount);
        assertEquals(1L, epochCount);
    }

    private static void bindDemoPrincipal() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new LocalDemoIdentity.DemoPrincipal(LocalDemoIdentity.USER_ID),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
    }
}

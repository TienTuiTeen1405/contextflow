package com.contextflow.context.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.contextflow.identity.demo.LocalDemoIdentity;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties")
@AutoConfigureMockMvc
@ActiveProfiles("local-demo")
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class ContextIndicatorControllerIntegrationTest {
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

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM contextflow.projects");
        jdbcTemplate.update("DELETE FROM contextflow.personal_skills");
        jdbcTemplate.update("DELETE FROM contextflow.ai_credentials");
        jdbcTemplate.update(
                "DELETE FROM contextflow.users WHERE id <> ?", LocalDemoIdentity.USER_ID);
        jdbcTemplate.update(
                """
                UPDATE contextflow.users
                SET personal_intelligence_enabled = true,
                    personal_profile_text = NULL,
                    personal_instructions = NULL,
                    status = 'ACTIVE'
                WHERE id = ?
                """,
                LocalDemoIdentity.USER_ID);
    }

    @Test
    void returnsContextIndicatorOnBothRoutesIgnoresForgedUserHeaderAndPreservesDbToggles()
            throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID convId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        UUID epochId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO contextflow.projects (id, owner_user_id, name, project_intelligence_enabled)
                VALUES (?, ?, 'Indicator Project', true)
                """,
                projectId,
                LocalDemoIdentity.USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.project_rules (id, project_id, name, content, enforcement_level, priority, is_enabled)
                VALUES (?, ?, 'M1', 'Rule 1', 'MANDATORY', 1, true),
                       (?, ?, 'O1', 'Rule 2', 'OVERRIDABLE', 2, true),
                       (?, ?, 'O2', 'Rule 3', 'OVERRIDABLE', 3, true)
                """,
                UUID.randomUUID(),
                projectId,
                UUID.randomUUID(),
                projectId,
                UUID.randomUUID(),
                projectId);
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.project_skills (id, project_id, name, content, priority, is_enabled)
                VALUES (?, ?, 'S1', 'Skill 1', 1, true)
                """,
                UUID.randomUUID(),
                projectId);
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.personal_skills (id, user_id, name, content, priority, is_enabled)
                VALUES (?, ?, 'PS1', 'Personal skill', 1, true)
                """,
                UUID.randomUUID(),
                LocalDemoIdentity.USER_ID);
        jdbcTemplate.update(
                "INSERT INTO contextflow.conversations (id, project_id, title) VALUES (?, ?, 'Conv')",
                convId,
                projectId);
        jdbcTemplate.update(
                "INSERT INTO contextflow.main_stages (id, project_id, conversation_id, stage_order, title) VALUES (?, ?, ?, 1, 'Main 1')",
                stageId,
                projectId,
                convId);
        insertEpoch(epochId, projectId, convId, stageId);
        UUID turnId = insertTurn(projectId, convId, stageId, epochId, 1, "Hello", "Hi there");
        insertContinuityEntry(
                projectId,
                convId,
                epochId,
                1,
                turnId,
                "[{\"type\":\"text\",\"text\":\"Hi there\"}]");
        insertCredential("USER", LocalDemoIdentity.USER_ID, "PAID");
        insertCredential("PLATFORM", null, "PAID");

        // Route 1: /api/v1/projects/{projectId}/stages/{stageId}/context-indicator (even with
        // forged X-User-Id)
        mockMvc.perform(
                        get(
                                        "/api/v1/projects/{projectId}/stages/{stageId}/context-indicator",
                                        projectId,
                                        stageId)
                                .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.conversationId").value(convId.toString()))
                .andExpect(jsonPath("$.mainStageId").value(stageId.toString()))
                .andExpect(jsonPath("$.personalIntelligenceEnabled").value(true))
                .andExpect(jsonPath("$.projectIntelligenceEnabled").value(true))
                .andExpect(jsonPath("$.mandatoryRules").value(1))
                .andExpect(jsonPath("$.overridableRules").value(2))
                .andExpect(jsonPath("$.activeSkills").value(2))
                .andExpect(jsonPath("$.activeWindowTurns").value(1))
                .andExpect(jsonPath("$.softCapTokens").value(32000))
                .andExpect(jsonPath("$.safetyMultiplier").value(1.15))
                .andExpect(jsonPath("$.rolloverSuggested").value(false))
                .andExpect(jsonPath("$.effectivePrivacyTier").value("PAID"))
                .andExpect(jsonPath("$.mixedTierWarning").value(false));

        // Turn OFF personal intelligence toggle and call Route 2
        // (/conversations/{convId}/stages/{stageId}/...)
        jdbcTemplate.update(
                "UPDATE contextflow.users SET personal_intelligence_enabled = false WHERE id = ?",
                LocalDemoIdentity.USER_ID);

        mockMvc.perform(
                        get(
                                "/api/v1/projects/{projectId}/conversations/{convId}/stages/{stageId}/context-indicator",
                                projectId,
                                convId,
                                stageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalIntelligenceEnabled").value(false))
                .andExpect(jsonPath("$.projectIntelligenceEnabled").value(true))
                .andExpect(jsonPath("$.mandatoryRules").value(1))
                .andExpect(jsonPath("$.overridableRules").value(2))
                .andExpect(jsonPath("$.activeSkills").value(1));

        // Verify personal_skills.is_enabled in DB was NEVER mutated
        Boolean personalSkillEnabledInDb =
                jdbcTemplate.queryForObject(
                        "SELECT is_enabled FROM contextflow.personal_skills WHERE user_id = ?",
                        Boolean.class,
                        LocalDemoIdentity.USER_ID);
        assertEquals(Boolean.TRUE, personalSkillEnabledInDb);
    }

    @Test
    void returns404OnCrossUserProjectAnd409OnProjectDeletingAndRolloverAbove32K() throws Exception {
        UUID otherUser = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.users (id, email, display_name) VALUES (?, ?, 'Other')",
                otherUser,
                otherUser + "@example.test");
        UUID foreignProject = UUID.randomUUID();
        UUID foreignConv = UUID.randomUUID();
        UUID foreignStage = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.projects (id, owner_user_id, name) VALUES (?, ?, 'OtherProj')",
                foreignProject,
                otherUser);
        jdbcTemplate.update(
                "INSERT INTO contextflow.conversations (id, project_id, title) VALUES (?, ?, 'C')",
                foreignConv,
                foreignProject);
        jdbcTemplate.update(
                "INSERT INTO contextflow.main_stages (id, project_id, conversation_id, stage_order, title) VALUES (?, ?, ?, 1, 'Main 1')",
                foreignStage,
                foreignProject,
                foreignConv);

        mockMvc.perform(
                        get(
                                "/api/v1/projects/{projectId}/stages/{stageId}/context-indicator",
                                foreignProject,
                                foreignStage))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        UUID ownProject = UUID.randomUUID();
        UUID ownConv = UUID.randomUUID();
        UUID ownStage = UUID.randomUUID();
        UUID ownEpoch = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.projects (id, owner_user_id, name) VALUES (?, ?, 'OwnProj')",
                ownProject,
                LocalDemoIdentity.USER_ID);
        jdbcTemplate.update(
                "INSERT INTO contextflow.conversations (id, project_id, title) VALUES (?, ?, 'OwnConv')",
                ownConv,
                ownProject);
        jdbcTemplate.update(
                "INSERT INTO contextflow.main_stages (id, project_id, conversation_id, stage_order, title) VALUES (?, ?, ?, 1, 'Main 1')",
                ownStage,
                ownProject,
                ownConv);
        insertEpoch(ownEpoch, ownProject, ownConv, ownStage);
        insertTurn(
                ownProject, ownConv, ownStage, ownEpoch, 1, "Old large turn", "z".repeat(120_000));
        UUID activeTurn = insertTurn(ownProject, ownConv, ownStage, ownEpoch, 2, "Recent", "Ok");
        insertContinuityEntry(
                ownProject,
                ownConv,
                ownEpoch,
                1,
                activeTurn,
                "[{\"type\":\"text\",\"text\":\"Ok\"}]");

        mockMvc.perform(
                        get(
                                "/api/v1/projects/{projectId}/conversations/{convId}/stages/{stageId}/context-indicator",
                                ownProject,
                                ownConv,
                                ownStage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyTruncated").value(true))
                .andExpect(jsonPath("$.rolloverSuggested").value(true))
                .andExpect(jsonPath("$.effectivePrivacyTier").value("UNKNOWN"));

        // Mismatched convId returns 404
        mockMvc.perform(
                        get(
                                "/api/v1/projects/{projectId}/conversations/{convId}/stages/{stageId}/context-indicator",
                                ownProject,
                                UUID.randomUUID(),
                                ownStage))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        // Sub-Branch query param never suggests Rollover even when parent history exceeded 32K
        UUID subBranchId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.sub_branches (
                    id, project_id, conversation_id, main_stage_id, anchor_turn_id, title
                )
                VALUES (?, ?, ?, ?, ?, 'Explore')
                """,
                subBranchId,
                ownProject,
                ownConv,
                ownStage,
                activeTurn);
        mockMvc.perform(
                        get(
                                        "/api/v1/projects/{projectId}/conversations/{convId}/stages/{stageId}/context-indicator",
                                        ownProject,
                                        ownConv,
                                        ownStage)
                                .param("subBranchId", subBranchId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subBranchId").value(subBranchId.toString()))
                .andExpect(jsonPath("$.rolloverSuggested").value(false));

        // DELETING project returns 409 PROJECT_DELETING
        jdbcTemplate.update(
                "UPDATE contextflow.projects SET status = 'DELETING' WHERE id = ?", ownProject);
        mockMvc.perform(
                        get(
                                "/api/v1/projects/{projectId}/stages/{stageId}/context-indicator",
                                ownProject,
                                ownStage))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PROJECT_DELETING"));
    }

    private void insertEpoch(UUID epochId, UUID projectId, UUID convId, UUID stageId) {
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.continuity_epochs (
                    id, project_id, conversation_id, main_stage_id,
                    route_kind, route_id, epoch_no, status,
                    provider, api_interface, model, compatibility_key
                )
                VALUES (?, ?, ?, ?, 'MAIN', ?, 1, 'ACTIVE', 'MOCK', 'INTERACTIONS', 'mock', ?)
                """,
                epochId,
                projectId,
                convId,
                stageId,
                stageId,
                "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    }

    private UUID insertTurn(
            UUID projectId,
            UUID convId,
            UUID stageId,
            UUID epochId,
            long sequenceNo,
            String userText,
            String assistantText) {
        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.ai_requests (
                    id, user_id, project_id, conversation_id, main_stage_id,
                    route_kind, route_id, epoch_id, idempotency_key, payload_hash,
                    correlation_id, status, finished_at
                )
                VALUES (?, ?, ?, ?, ?, 'MAIN', ?, ?, ?, ?, ?, 'COMPLETED', now())
                """,
                requestId,
                LocalDemoIdentity.USER_ID,
                projectId,
                convId,
                stageId,
                stageId,
                epochId,
                UUID.randomUUID().toString(),
                "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8),
                UUID.randomUUID());

        UUID turnId = UUID.randomUUID();
        String blocksJson = "[{\"type\":\"TEXT\",\"text\":\"" + userText + "\"}]";
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.turns (
                    id, project_id, conversation_id, main_stage_id,
                    request_id, sequence_no, user_content_blocks, assistant_text
                )
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                turnId,
                projectId,
                convId,
                stageId,
                requestId,
                sequenceNo,
                blocksJson,
                assistantText);
        return turnId;
    }

    private void insertContinuityEntry(
            UUID projectId,
            UUID convId,
            UUID epochId,
            long ordinal,
            UUID turnId,
            String nativeStepsJson) {
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.continuity_turn_entries (
                    project_id, conversation_id, epoch_id, ordinal, turn_id, native_output_steps
                )
                VALUES (?, ?, ?, ?, ?, ?::json)
                """,
                projectId,
                convId,
                epochId,
                ordinal,
                turnId,
                nativeStepsJson);
    }

    private void insertCredential(String ownerScope, UUID ownerUserId, String tier) {
        byte[] dummy = new byte[] {1, 2, 3};
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.ai_credentials (
                    id, owner_scope, owner_user_id, credential_version, status,
                    masked_label, encrypted_secret, wrapped_dek, kek_version, declared_tier
                )
                VALUES (?, ?, ?, 1, 'ACTIVE', 'AIza...test', ?, ?, 'v1', ?)
                """,
                UUID.randomUUID(),
                ownerScope,
                ownerUserId,
                dummy,
                dummy,
                tier);
    }
}

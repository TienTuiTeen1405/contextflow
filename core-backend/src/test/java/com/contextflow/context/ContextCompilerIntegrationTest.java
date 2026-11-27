package com.contextflow.context;

import static org.junit.jupiter.api.Assertions.*;

import com.contextflow.common.api.ApiProblem;
import com.contextflow.common.api.ErrorCode;
import com.contextflow.context.dto.CompiledContextResult;
import com.contextflow.context.dto.ContentBlockDto;
import com.contextflow.context.dto.ContextCompileRequest;
import com.contextflow.context.dto.InstructionItemDto;
import com.contextflow.context.dto.PrivacyTier;
import com.contextflow.context.service.ContextCompilerService;
import com.contextflow.identity.demo.LocalDemoIdentity;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties")
@ActiveProfiles("local-demo")
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class ContextCompilerIntegrationTest {
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

    private final JdbcTemplate jdbcTemplate;
    private final ContextCompilerService contextCompilerService;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
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
    void compilesThreeLayersRespectsTogglesWithoutDbMutationAndCapsActiveWindow() {
        bindDemoPrincipal();
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        UUID activeEpochId = UUID.randomUUID();
        UUID invalidatedEpochId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                UPDATE contextflow.users
                SET personal_intelligence_enabled = true,
                    personal_profile_text = 'Backend Engineer',
                    personal_instructions = 'Respond concisely'
                WHERE id = ?
                """,
                LocalDemoIdentity.USER_ID);
        UUID pSkillId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.personal_skills (id, user_id, name, content, priority, is_enabled)
                VALUES (?, ?, 'Personal Skill', 'Use crisp bullet points', 5, true)
                """,
                pSkillId,
                LocalDemoIdentity.USER_ID);

        jdbcTemplate.update(
                """
                INSERT INTO contextflow.projects
                    (id, owner_user_id, name, project_information, project_intelligence_enabled, status)
                VALUES (?, ?, 'Compiler IT', 'ContextFlow V6 Backend', true, 'ACTIVE')
                """,
                projectId,
                LocalDemoIdentity.USER_ID);

        UUID mRule2 = UUID.randomUUID();
        UUID mRule1 = UUID.randomUUID();
        UUID oRule1 = UUID.randomUUID();
        UUID projSkillId = UUID.randomUUID();
        Instant baseTime = Instant.parse("2026-11-26T00:00:00Z");

        jdbcTemplate.update(
                """
                INSERT INTO contextflow.project_rules
                    (id, project_id, name, content, enforcement_level, priority, is_enabled, created_at)
                VALUES (?, ?, 'M2', 'Second mandatory', 'MANDATORY', 20, true, ?),
                       (?, ?, 'M1', 'First mandatory', 'MANDATORY', 10, true, ?),
                       (?, ?, 'O1', 'First overridable', 'OVERRIDABLE', 5, true, ?)
                """,
                mRule2,
                projectId,
                Timestamp.from(baseTime.plusSeconds(10)),
                mRule1,
                projectId,
                Timestamp.from(baseTime),
                oRule1,
                projectId,
                Timestamp.from(baseTime));

        jdbcTemplate.update(
                """
                INSERT INTO contextflow.project_skills
                    (id, project_id, name, content, priority, is_enabled)
                VALUES (?, ?, 'Project Skill', 'Cite PostgreSQL tables', 3, true)
                """,
                projSkillId,
                projectId);

        jdbcTemplate.update(
                "INSERT INTO contextflow.conversations (id, project_id, title) VALUES (?, ?, 'Conv')",
                conversationId,
                projectId);
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.main_stages (id, project_id, conversation_id, stage_order, title)
                VALUES (?, ?, ?, 1, 'Main 1')
                """,
                stageId,
                projectId,
                conversationId);

        insertEpoch(invalidatedEpochId, projectId, conversationId, stageId, 1, "INVALIDATED");
        insertEpoch(activeEpochId, projectId, conversationId, stageId, 2, "ACTIVE");

        // Old invalidated turn must NOT enter Active Window, but goes into non-active history
        UUID oldInvalidatedTurn =
                insertTurn(
                        projectId,
                        conversationId,
                        stageId,
                        invalidatedEpochId,
                        1,
                        "[{\"type\":\"TEXT\",\"text\":\"Invalidated epoch prompt\"}]",
                        "Invalidated epoch answer");
        insertContinuityEntry(
                projectId,
                conversationId,
                invalidatedEpochId,
                1,
                oldInvalidatedTurn,
                "[{\"type\":\"text\",\"text\":\"Invalidated epoch answer\"}]");

        // 10 turns on ACTIVE epoch: Active Window keeps latest 8, oldest 2 move to Tier 3 history
        for (int i = 2; i <= 11; i++) {
            if (i == 4) {
                UUID quoteId = UUID.randomUUID();
                UUID turnWithQuote =
                        insertTurn(
                                projectId,
                                conversationId,
                                stageId,
                                activeEpochId,
                                i,
                                "[{\"type\":\"TEXT\",\"text\":\"Compare \"},{\"type\":\"QUOTE_REF\",\"quoteRefId\":\""
                                        + quoteId
                                        + "\"}]",
                                "Answer " + i);
                jdbcTemplate.update(
                        """
                        INSERT INTO contextflow.turn_quote_references (
                            id, project_id, conversation_id, turn_id,
                            source_kind, source_id, source_revision, locator,
                            content_hash, snapshot_text, exclude_from_auto_kb, link_status
                        )
                        VALUES (?, ?, ?, ?, 'MAIN_TURN', ?, '1', '{"charStart":0,"charEnd":8,"prefixAnchor":"","suffixAnchor":""}'::jsonb, ?, 'Quoted snapshot 4', false, 'AVAILABLE')
                        """,
                        quoteId,
                        projectId,
                        conversationId,
                        turnWithQuote,
                        oldInvalidatedTurn,
                        "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
                insertContinuityEntry(
                        projectId,
                        conversationId,
                        activeEpochId,
                        i - 1L,
                        turnWithQuote,
                        "[{\"type\":\"text\",\"text\":\"Answer " + i + "\"}]");
                continue;
            }
            UUID turnId =
                    insertTurn(
                            projectId,
                            conversationId,
                            stageId,
                            activeEpochId,
                            i,
                            "[{\"type\":\"TEXT\",\"text\":\"Turn " + i + "\"}]",
                            "Answer " + i);
            insertContinuityEntry(
                    projectId,
                    conversationId,
                    activeEpochId,
                    i - 1L,
                    turnId,
                    "[{\"type\":\"text\",\"text\":\"Answer " + i + "\"}]");
        }

        // Seed credentials: USER PAID + PLATFORM FREE -> effectivePrivacyTier FREE +
        // mixedTierWarning
        insertCredential("USER", LocalDemoIdentity.USER_ID, "PAID");
        insertCredential("PLATFORM", null, "FREE");

        CompiledContextResult compiled =
                contextCompilerService.compile(
                        ContextCompileRequest.builder()
                                .projectId(projectId)
                                .conversationId(conversationId)
                                .mainStageId(stageId)
                                .currentUserContentBlocks(
                                        List.of(ContentBlockDto.textBlock("Current prompt")))
                                .build());

        assertTrue(compiled.isPersonalIntelligenceEnabled());
        assertTrue(compiled.isProjectIntelligenceEnabled());
        assertEquals(2, compiled.getMandatoryRulesCount());
        assertEquals(1, compiled.getOverridableRulesCount());
        assertEquals(2, compiled.getActiveSkillsCount());
        assertEquals(8, compiled.getActiveWindowTurnsCount());
        assertEquals(3, compiled.getHistoryTurnsCount()); // invalidated + turn 2 + turn 3
        assertEquals(PrivacyTier.FREE, compiled.getEffectivePrivacyTier());
        assertTrue(compiled.isMixedTierWarning());
        assertEquals(
                List.of("First mandatory", "Second mandatory"),
                compiled
                        .getPromptEnvelope()
                        .getIntelligenceContext()
                        .getInstructions()
                        .getMandatoryRules()
                        .stream()
                        .map(InstructionItemDto::getContent)
                        .toList());
        assertEquals(
                List.of("Compare "),
                compiled.getPromptEnvelope().getContinuity().getFirst().getUserTextBlocks());
        assertEquals(
                1,
                compiled.getPromptEnvelope()
                        .getContinuity()
                        .getFirst()
                        .getHistoricalQuotes()
                        .size());
        assertEquals(
                "Quoted snapshot 4",
                compiled.getPromptEnvelope()
                        .getContinuity()
                        .getFirst()
                        .getHistoricalQuotes()
                        .getFirst()
                        .getSnapshotText());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UUID.fromString(
                                compiled.getPromptEnvelope()
                                        .getContinuity()
                                        .getFirst()
                                        .getHistoricalQuotes()
                                        .getFirst()
                                        .getQuoteRefId()));
        assertEquals(
                List.of("Turn 11"),
                compiled.getPromptEnvelope().getContinuity().getLast().getUserTextBlocks());

        // Turn OFF project toggle & verify rules/skills excluded while DB is_enabled stays true!
        jdbcTemplate.update(
                "UPDATE contextflow.projects SET project_intelligence_enabled = false WHERE id = ?",
                projectId);
        CompiledContextResult projectOff =
                contextCompilerService.compile(
                        ContextCompileRequest.builder()
                                .projectId(projectId)
                                .conversationId(conversationId)
                                .mainStageId(stageId)
                                .build());

        assertFalse(projectOff.isProjectIntelligenceEnabled());
        assertEquals(0, projectOff.getMandatoryRulesCount());
        assertEquals(0, projectOff.getOverridableRulesCount());
        assertEquals(1, projectOff.getActiveSkillsCount()); // only personal skill remains
        assertNull(
                projectOff
                        .getPromptEnvelope()
                        .getIntelligenceContext()
                        .getBackground()
                        .getProjectInformation());
        Integer enabledRulesInDb =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM contextflow.project_rules WHERE project_id = ? AND is_enabled = true",
                        Integer.class,
                        projectId);
        Integer enabledSkillsInDb =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM contextflow.project_skills WHERE project_id = ? AND is_enabled = true",
                        Integer.class,
                        projectId);
        assertEquals(3, enabledRulesInDb);
        assertEquals(1, enabledSkillsInDb);
    }

    @Test
    void enforcesCrossUser404Deleting409AndOver32KTruncationInRealPostgres() {
        bindDemoPrincipal();
        UUID otherUser = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.users (id, email, display_name) VALUES (?, ?, 'Other')",
                otherUser,
                otherUser + "@example.test");
        UUID foreignProject = UUID.randomUUID();
        UUID foreignConv = UUID.randomUUID();
        UUID foreignStage = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.projects (id, owner_user_id, name) VALUES (?, ?, 'Foreign')",
                foreignProject,
                otherUser);
        jdbcTemplate.update(
                "INSERT INTO contextflow.conversations (id, project_id, title) VALUES (?, ?, 'FC')",
                foreignConv,
                foreignProject);
        jdbcTemplate.update(
                "INSERT INTO contextflow.main_stages (id, project_id, conversation_id, stage_order, title) VALUES (?, ?, ?, 1, 'Main 1')",
                foreignStage,
                foreignProject,
                foreignConv);

        ApiProblem notFound =
                assertThrows(
                        ApiProblem.class,
                        () ->
                                contextCompilerService.compileForStageIndicator(
                                        foreignProject, foreignConv, foreignStage, null));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, notFound.code());

        // Own project with >32K history triggers truncation & rolloverSuggested while protecting
        // mandatory rules
        UUID ownProject = UUID.randomUUID();
        UUID ownConv = UUID.randomUUID();
        UUID ownStage = UUID.randomUUID();
        UUID ownEpoch = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.projects (id, owner_user_id, name) VALUES (?, ?, 'Heavy')",
                ownProject,
                LocalDemoIdentity.USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.project_rules (id, project_id, name, content, enforcement_level, priority)
                VALUES (?, ?, 'KeepMe', 'INVARIANT_MANDATORY_RULE', 'MANDATORY', 1)
                """,
                UUID.randomUUID(),
                ownProject);
        jdbcTemplate.update(
                "INSERT INTO contextflow.conversations (id, project_id, title) VALUES (?, ?, 'C')",
                ownConv,
                ownProject);
        jdbcTemplate.update(
                "INSERT INTO contextflow.main_stages (id, project_id, conversation_id, stage_order, title) VALUES (?, ?, ?, 1, 'Main 1')",
                ownStage,
                ownProject,
                ownConv);
        insertEpoch(ownEpoch, ownProject, ownConv, ownStage, 1, "ACTIVE");

        String hugeAssistant = "x".repeat(120_000); // ~34,500 tokens per turn
        insertTurn(
                ownProject,
                ownConv,
                ownStage,
                ownEpoch,
                1,
                "[{\"type\":\"TEXT\",\"text\":\"Old heavy turn\"}]",
                hugeAssistant);
        UUID activeTurn =
                insertTurn(
                        ownProject,
                        ownConv,
                        ownStage,
                        ownEpoch,
                        2,
                        "[{\"type\":\"TEXT\",\"text\":\"Active small turn\"}]",
                        "Small reply");
        insertContinuityEntry(
                ownProject,
                ownConv,
                ownEpoch,
                1,
                activeTurn,
                "[{\"type\":\"text\",\"text\":\"Small reply\"}]");

        CompiledContextResult truncated =
                contextCompilerService.compile(
                        ContextCompileRequest.builder()
                                .projectId(ownProject)
                                .conversationId(ownConv)
                                .mainStageId(ownStage)
                                .currentUserContentBlocks(
                                        List.of(ContentBlockDto.textBlock("MUST_STAY_REQUEST")))
                                .build());

        assertTrue(truncated.getEstimatedTokensBeforeTruncation() > 32_000);
        assertTrue(truncated.getEstimatedInputTokens() <= 32_000);
        assertTrue(truncated.isHistoryTruncated());
        assertTrue(truncated.isRolloverSuggested());
        assertEquals(1, truncated.getMandatoryRulesCount());
        assertEquals(
                "MUST_STAY_REQUEST",
                truncated
                        .getPromptEnvelope()
                        .getCurrentUserRequest()
                        .getContentBlocks()
                        .getFirst()
                        .getText());

        // Switching ownProject to DELETING returns 409 PROJECT_DELETING
        jdbcTemplate.update(
                "UPDATE contextflow.projects SET status = 'DELETING' WHERE id = ?", ownProject);
        ApiProblem deleting =
                assertThrows(
                        ApiProblem.class,
                        () ->
                                contextCompilerService.compileForStageIndicator(
                                        ownProject, ownConv, ownStage, null));
        assertEquals(ErrorCode.PROJECT_DELETING, deleting.code());
    }

    private void insertEpoch(
            UUID epochId, UUID projectId, UUID convId, UUID stageId, long epochNo, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.continuity_epochs (
                    id, project_id, conversation_id, main_stage_id,
                    route_kind, route_id, epoch_no, status,
                    provider, api_interface, model, compatibility_key
                )
                VALUES (?, ?, ?, ?, 'MAIN', ?, ?, ?, 'MOCK', 'INTERACTIONS', 'mock', ?)
                """,
                epochId,
                projectId,
                convId,
                stageId,
                stageId,
                epochNo,
                status,
                "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    }

    private UUID insertTurn(
            UUID projectId,
            UUID convId,
            UUID stageId,
            UUID epochId,
            long sequenceNo,
            String blocksJson,
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

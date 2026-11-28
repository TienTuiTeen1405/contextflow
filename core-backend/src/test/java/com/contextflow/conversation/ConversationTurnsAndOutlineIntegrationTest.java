package com.contextflow.conversation;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contextflow.identity.demo.LocalDemoIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** PR-4B: read committed Main Stage turns and the conversation outline. */
@SpringBootTest(properties = "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties")
@AutoConfigureMockMvc
@ActiveProfiles("local-demo")
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class ConversationTurnsAndOutlineIntegrationTest {
    private static final String TURNS_URL = "/api/v1/projects/{projectId}/stages/{stageId}/turns";
    private static final String OUTLINE_URL =
            "/api/v1/projects/{projectId}/conversations/{conversationId}/outline";
    private static final UUID DEMO_USER = LocalDemoIdentity.USER_ID;

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
        jdbcTemplate.update("DELETE FROM contextflow.users WHERE id <> ?", DEMO_USER);
    }

    @Test
    void readsCommittedMainTurnsInSequenceOrderWithCursors() throws Exception {
        UUID projectId = insertProject(DEMO_USER);
        UUID conversationId = insertConversation(projectId);
        UUID mainOne = insertStage(projectId, conversationId, 1, null, "ACTIVE");
        UUID mainTwo = insertStage(projectId, conversationId, 2, mainOne, "ACTIVE");
        Map<Long, UUID> turnIds = new HashMap<>();
        for (long sequenceNo : new long[] {3, 1, 5, 2, 4}) {
            turnIds.put(
                    sequenceNo,
                    insertTurn(
                            projectId,
                            conversationId,
                            mainOne,
                            null,
                            sequenceNo,
                            textBlocks("Câu hỏi " + sequenceNo),
                            "Trả lời " + sequenceNo));
        }
        UUID subBranchId = insertSubBranch(projectId, conversationId, mainOne, turnIds.get(1L));
        insertTurn(
                projectId,
                conversationId,
                mainOne,
                subBranchId,
                1,
                textBlocks("Nhánh phụ"),
                "Không thuộc Main");
        UUID mainTwoTurn =
                insertTurn(
                        projectId,
                        conversationId,
                        mainTwo,
                        null,
                        1,
                        textBlocks("Main 2"),
                        "Main 2");

        mockMvc.perform(get(TURNS_URL, projectId, mainOne))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[*].sequenceNo", contains(1, 2, 3, 4, 5)))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextAfterSequence").value(5))
                .andExpect(jsonPath("$.items[0].id").value(turnIds.get(1L).toString()))
                .andExpect(jsonPath("$.items[0].projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.items[0].conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.items[0].mainStageId").value(mainOne.toString()))
                .andExpect(jsonPath("$.items[0].subBranchId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].chapterId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].sourceRevision").value(1))
                .andExpect(jsonPath("$.items[0].userContentBlocks", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userContentBlocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.items[0].userContentBlocks[0].text").value("Câu hỏi 1"))
                .andExpect(jsonPath("$.items[0].assistantText").value("Trả lời 1"))
                .andExpect(jsonPath("$.items[0].quotes").isEmpty())
                .andExpect(jsonPath("$.items[0].mainReferences").isEmpty())
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty());

        expectPage(get(TURNS_URL, projectId, mainOne).param("limit", "2"), true, 2L, 1, 2);
        expectPage(
                get(TURNS_URL, projectId, mainOne).param("afterSequence", "2").param("limit", "2"),
                true,
                4L,
                3,
                4);
        expectPage(
                get(TURNS_URL, projectId, mainOne).param("afterSequence", "4").param("limit", "2"),
                false,
                5L,
                5);
        expectPage(get(TURNS_URL, projectId, mainOne).param("afterSequence", "5"), false, 5L);
        expectPage(
                get(TURNS_URL, projectId, mainOne).param("beforeSequence", "5").param("limit", "2"),
                true,
                4L,
                3,
                4);
        expectPage(
                get(TURNS_URL, projectId, mainOne).param("beforeSequence", "3").param("limit", "2"),
                false,
                2L,
                1,
                2);
        expectPage(get(TURNS_URL, projectId, mainOne).param("beforeSequence", "1"), false, null);

        mockMvc.perform(get(TURNS_URL, projectId, mainTwo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(mainTwoTurn.toString()));
    }

    @Test
    void mapsStoredContentBlocksQuotesAndMainReferences() throws Exception {
        UUID projectId = insertProject(DEMO_USER);
        UUID conversationId = insertConversation(projectId);
        UUID mainOne = insertStage(projectId, conversationId, 1, null, "ACTIVE");
        String snapshot = "Dùng PostgreSQL chung.";
        UUID sourceTurn =
                insertTurn(
                        projectId, conversationId, mainOne, null, 1, textBlocks("Nguồn"), snapshot);
        UUID quoteId = UUID.randomUUID();
        String blocks =
                "[{\"type\":\"TEXT\",\"text\":\"Đối chiếu \"},"
                        + "{\"type\":\"QUOTE_REF\",\"quoteRefId\":\""
                        + quoteId
                        + "\"},"
                        + "{\"type\":\"TEXT\",\"text\":\" giúp mình.\"}]";
        UUID turnId =
                insertTurn(projectId, conversationId, mainOne, null, 2, blocks, "Phản hồi mẫu.");
        byte[] hash =
                MessageDigest.getInstance("SHA-256")
                        .digest(snapshot.getBytes(StandardCharsets.UTF_8));
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.turn_quote_references (
                    id, project_id, conversation_id, turn_id, source_kind, source_id,
                    source_revision, locator, content_hash, snapshot_text,
                    exclude_from_auto_kb, link_status
                )
                VALUES (?, ?, ?, ?, 'MAIN_TURN', ?, '1', ?::jsonb, ?, ?, true, 'AVAILABLE')
                """,
                quoteId,
                projectId,
                conversationId,
                turnId,
                sourceTurn,
                "{\"charStart\":0,\"charEnd\":22,\"prefixAnchor\":\"\",\"suffixAnchor\":\"\"}",
                hash,
                snapshot);
        insertMainReference(
                projectId, conversationId, turnId, "STAGE", mainOne, null, "2026-11-01");
        insertMainReference(
                projectId, conversationId, turnId, "TURN", mainOne, sourceTurn, "2026-11-02");

        mockMvc.perform(get(TURNS_URL, projectId, mainOne).param("afterSequence", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(turnId.toString()))
                .andExpect(jsonPath("$.items[0].userContentBlocks", hasSize(3)))
                .andExpect(jsonPath("$.items[0].userContentBlocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.items[0].userContentBlocks[0].text").value("Đối chiếu "))
                .andExpect(jsonPath("$.items[0].userContentBlocks[0].quoteRefId").doesNotExist())
                .andExpect(jsonPath("$.items[0].userContentBlocks[1].type").value("QUOTE_REF"))
                .andExpect(
                        jsonPath("$.items[0].userContentBlocks[1].quoteRefId")
                                .value(quoteId.toString()))
                .andExpect(jsonPath("$.items[0].userContentBlocks[1].text").doesNotExist())
                .andExpect(jsonPath("$.items[0].quotes", hasSize(1)))
                .andExpect(jsonPath("$.items[0].quotes[0].quoteRefId").value(quoteId.toString()))
                .andExpect(jsonPath("$.items[0].quotes[0].sourceKind").value("MAIN_TURN"))
                .andExpect(jsonPath("$.items[0].quotes[0].sourceId").value(sourceTurn.toString()))
                .andExpect(jsonPath("$.items[0].quotes[0].sourceRevision").value("1"))
                .andExpect(jsonPath("$.items[0].quotes[0].locator.charStart").value(0))
                .andExpect(jsonPath("$.items[0].quotes[0].locator.charEnd").value(22))
                .andExpect(jsonPath("$.items[0].quotes[0].locator.prefixAnchor").value(""))
                .andExpect(jsonPath("$.items[0].quotes[0].locator.suffixAnchor").value(""))
                .andExpect(
                        jsonPath("$.items[0].quotes[0].contentHashSha256Hex")
                                .value(HexFormat.of().formatHex(hash)))
                .andExpect(jsonPath("$.items[0].quotes[0].snapshotText").value(snapshot))
                .andExpect(jsonPath("$.items[0].quotes[0].linkStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.items[0].quotes[0].excludeFromAutoKb").value(true))
                .andExpect(jsonPath("$.items[0].mainReferences", hasSize(2)))
                .andExpect(jsonPath("$.items[0].mainReferences[0].granularity").value("STAGE"))
                .andExpect(
                        jsonPath("$.items[0].mainReferences[0].targetStageId")
                                .value(mainOne.toString()))
                .andExpect(
                        jsonPath("$.items[0].mainReferences[0].targetChapterId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].mainReferences[0].targetTurnId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].mainReferences[1].granularity").value("TURN"))
                .andExpect(
                        jsonPath("$.items[0].mainReferences[1].targetTurnId")
                                .value(sourceTurn.toString()));
    }

    @Test
    void turnsRejectInvalidCursorParameters() throws Exception {
        UUID projectId = insertProject(DEMO_USER);
        UUID conversationId = insertConversation(projectId);
        UUID mainOne = insertStage(projectId, conversationId, 1, null, "ACTIVE");

        for (Map<String, String> params :
                List.of(
                        Map.of("afterSequence", "1", "beforeSequence", "3"),
                        Map.of("limit", "0"),
                        Map.of("limit", "101"),
                        Map.of("limit", "abc"),
                        Map.of("afterSequence", "-1"),
                        Map.of("beforeSequence", "-1"),
                        Map.of("afterSequence", "x"))) {
            MockHttpServletRequestBuilder request = get(TURNS_URL, projectId, mainOne);
            params.forEach(request::param);
            expectError(request, 400, "VALIDATION_ERROR");
        }
        expectError(
                get("/api/v1/projects/{projectId}/stages/not-a-uuid/turns", projectId),
                400,
                "VALIDATION_ERROR");
    }

    @Test
    void turnsEnforceStageScopeAndLifecycle() throws Exception {
        UUID projectA = insertProject(DEMO_USER);
        UUID projectB = insertProject(DEMO_USER);
        UUID conversationId = insertConversation(projectA);
        UUID mainOne = insertStage(projectA, conversationId, 1, null, "ACTIVE");
        UUID foreignProject = insertProject(insertUser());
        UUID foreignConversation = insertConversation(foreignProject);
        UUID foreignStage = insertStage(foreignProject, foreignConversation, 1, null, "ACTIVE");

        expectError(get(TURNS_URL, projectB, mainOne), 404, "RESOURCE_NOT_FOUND");
        expectError(get(TURNS_URL, projectA, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        expectError(get(TURNS_URL, foreignProject, foreignStage), 404, "RESOURCE_NOT_FOUND");

        jdbcTemplate.update(
                "UPDATE contextflow.main_stages SET status = 'DELETING' WHERE id = ?", mainOne);
        expectError(get(TURNS_URL, projectA, mainOne), 404, "RESOURCE_NOT_FOUND");
        jdbcTemplate.update(
                "UPDATE contextflow.main_stages SET status = 'ACTIVE' WHERE id = ?", mainOne);

        setConversationStatus(conversationId, "DELETING");
        expectError(get(TURNS_URL, projectA, mainOne), 404, "RESOURCE_NOT_FOUND");
        setConversationStatus(conversationId, "ACTIVE");

        setProjectStatus(projectA, "DELETING");
        expectError(get(TURNS_URL, projectA, mainOne), 409, "PROJECT_DELETING");
    }

    @Test
    void outlineListsStagesByStageOrderWithEmptyChaptersAndSubBranches() throws Exception {
        UUID projectId = insertProject(DEMO_USER);
        UUID conversationId = insertConversation(projectId);
        UUID mainThree = insertStage(projectId, conversationId, 3, null, "LOCKED_READONLY");
        UUID mainOne = insertStage(projectId, conversationId, 1, null, "ACTIVE");
        UUID mainTwo = insertStage(projectId, conversationId, 2, mainOne, "ACTIVE");
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.chapters (
                    id, project_id, conversation_id, main_stage_id, title, display_order
                )
                VALUES (?, ?, ?, ?, 'Chương 1', 1)
                """,
                UUID.randomUUID(),
                projectId,
                conversationId,
                mainOne);
        UUID anchorTurn =
                insertTurn(projectId, conversationId, mainOne, null, 1, textBlocks("Neo"), "Ok");
        insertSubBranch(projectId, conversationId, mainOne, anchorTurn);
        UUID otherConversation = insertConversation(projectId);
        insertStage(projectId, otherConversation, 1, null, "ACTIVE");

        mockMvc.perform(get(OUTLINE_URL, projectId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.stages", hasSize(3)))
                .andExpect(
                        jsonPath(
                                "$.stages[*].id",
                                contains(
                                        mainOne.toString(),
                                        mainTwo.toString(),
                                        mainThree.toString())))
                .andExpect(jsonPath("$.stages[*].stageOrder", contains(1, 2, 3)))
                .andExpect(jsonPath("$.stages[0].parentStageId").value(nullValue()))
                .andExpect(jsonPath("$.stages[0].title").value("Main 1"))
                .andExpect(jsonPath("$.stages[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.stages[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.stages[1].parentStageId").value(mainOne.toString()))
                .andExpect(jsonPath("$.stages[2].status").value("LOCKED_READONLY"))
                .andExpect(jsonPath("$.chapters").isArray())
                .andExpect(jsonPath("$.chapters").isEmpty())
                .andExpect(jsonPath("$.subBranches").isArray())
                .andExpect(jsonPath("$.subBranches").isEmpty());
    }

    @Test
    void outlineEnforcesConversationScopeAndLifecycle() throws Exception {
        UUID projectA = insertProject(DEMO_USER);
        UUID projectB = insertProject(DEMO_USER);
        UUID conversationId = insertConversation(projectA);
        insertStage(projectA, conversationId, 1, null, "ACTIVE");
        UUID foreignProject = insertProject(insertUser());
        UUID foreignConversation = insertConversation(foreignProject);

        expectError(get(OUTLINE_URL, projectB, conversationId), 404, "RESOURCE_NOT_FOUND");
        expectError(get(OUTLINE_URL, projectA, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        expectError(
                get(OUTLINE_URL, foreignProject, foreignConversation), 404, "RESOURCE_NOT_FOUND");
        expectError(
                get("/api/v1/projects/{projectId}/conversations/not-a-uuid/outline", projectA),
                400,
                "VALIDATION_ERROR");

        setConversationStatus(conversationId, "DELETING");
        expectError(get(OUTLINE_URL, projectA, conversationId), 404, "RESOURCE_NOT_FOUND");
        setConversationStatus(conversationId, "ACTIVE");

        setProjectStatus(projectA, "DELETING");
        expectError(get(OUTLINE_URL, projectA, conversationId), 409, "PROJECT_DELETING");
    }

    private void expectPage(
            MockHttpServletRequestBuilder request,
            boolean hasMore,
            Long nextAfterSequence,
            Integer... sequenceNumbers)
            throws Exception {
        ResultActions actions =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items", hasSize(sequenceNumbers.length)))
                        .andExpect(jsonPath("$.hasMore").value(hasMore));
        if (sequenceNumbers.length > 0) {
            actions.andExpect(jsonPath("$.items[*].sequenceNo", contains(sequenceNumbers)));
        }
        if (nextAfterSequence == null) {
            actions.andExpect(jsonPath("$.nextAfterSequence").value(nullValue()));
        } else {
            actions.andExpect(jsonPath("$.nextAfterSequence").value(nextAfterSequence.intValue()));
        }
    }

    private void expectError(MockHttpServletRequestBuilder request, int httpStatus, String code)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.users (id, email, display_name) VALUES (?, ?, 'Other')",
                userId,
                userId + "@example.test");
        return userId;
    }

    private UUID insertProject(UUID ownerUserId) {
        UUID projectId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.projects (id, owner_user_id, name) VALUES (?, ?, 'Dự án')",
                projectId,
                ownerUserId);
        return projectId;
    }

    private UUID insertConversation(UUID projectId) {
        UUID conversationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.conversations (id, project_id, title) VALUES (?, ?, 'Hội thoại')",
                conversationId,
                projectId);
        return conversationId;
    }

    private UUID insertStage(
            UUID projectId,
            UUID conversationId,
            int stageOrder,
            UUID parentStageId,
            String stageStatus) {
        UUID stageId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.main_stages (
                    id, project_id, conversation_id, parent_stage_id, stage_order, title, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                stageId,
                projectId,
                conversationId,
                parentStageId,
                stageOrder,
                "Main " + stageOrder,
                stageStatus);
        return stageId;
    }

    private UUID insertSubBranch(
            UUID projectId, UUID conversationId, UUID stageId, UUID anchorTurnId) {
        UUID subBranchId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.sub_branches (
                    id, project_id, conversation_id, main_stage_id, anchor_turn_id, title
                )
                VALUES (?, ?, ?, ?, ?, 'Nhánh phụ')
                """,
                subBranchId,
                projectId,
                conversationId,
                stageId,
                anchorTurnId);
        return subBranchId;
    }

    /** Inserts a completed chat request and its committed Turn, as the Chat Worker would. */
    private UUID insertTurn(
            UUID projectId,
            UUID conversationId,
            UUID stageId,
            UUID subBranchId,
            long sequenceNo,
            String userContentBlocksJson,
            String assistantText) {
        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.ai_requests (
                    id, user_id, project_id, conversation_id, main_stage_id, sub_branch_id,
                    route_kind, route_id, idempotency_key, payload_hash, correlation_id,
                    status, finished_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED', now())
                """,
                requestId,
                DEMO_USER,
                projectId,
                conversationId,
                stageId,
                subBranchId,
                subBranchId == null ? "MAIN" : "SUB_BRANCH",
                subBranchId == null ? stageId : subBranchId,
                UUID.randomUUID().toString(),
                new byte[32],
                UUID.randomUUID());
        UUID turnId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.turns (
                    id, project_id, conversation_id, main_stage_id, sub_branch_id,
                    request_id, sequence_no, user_content_blocks, assistant_text
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                turnId,
                projectId,
                conversationId,
                stageId,
                subBranchId,
                requestId,
                sequenceNo,
                userContentBlocksJson,
                assistantText);
        return turnId;
    }

    private void insertMainReference(
            UUID projectId,
            UUID conversationId,
            UUID turnId,
            String granularity,
            UUID targetStageId,
            UUID targetTurnId,
            String createdDate) {
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.turn_main_references (
                    id, project_id, conversation_id, turn_id, granularity,
                    target_stage_id, target_turn_id, display_label, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, '@Main', ?)
                """,
                UUID.randomUUID(),
                projectId,
                conversationId,
                turnId,
                granularity,
                targetStageId,
                targetTurnId,
                OffsetDateTime.parse(createdDate + "T00:00:00Z"));
    }

    private void setConversationStatus(UUID conversationId, String conversationStatus) {
        jdbcTemplate.update(
                "UPDATE contextflow.conversations SET status = ? WHERE id = ?",
                conversationStatus,
                conversationId);
    }

    private void setProjectStatus(UUID projectId, String projectStatus) {
        jdbcTemplate.update(
                "UPDATE contextflow.projects SET status = ? WHERE id = ?",
                projectStatus,
                projectId);
    }

    private static String textBlocks(String text) {
        return "[{\"type\":\"TEXT\",\"text\":\"" + text + "\"}]";
    }
}

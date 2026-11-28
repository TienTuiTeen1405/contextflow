package com.contextflow.conversation;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contextflow.identity.demo.LocalDemoIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** PR-4A: list, create and rename conversations against PostgreSQL 16 + pgvector. */
@SpringBootTest(properties = "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties")
@AutoConfigureMockMvc
@ActiveProfiles("local-demo")
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class ConversationCrudIntegrationTest {
    private static final String CONVERSATIONS_URL = "/api/v1/projects/{projectId}/conversations";
    private static final String CONVERSATION_URL =
            "/api/v1/projects/{projectId}/conversations/{conversationId}";
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
    private final ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM contextflow.projects");
        jdbcTemplate.update("DELETE FROM contextflow.users WHERE id <> ?", DEMO_USER);
    }

    @Test
    void createsConversationWithMainOneAndActiveEpochInOneTransaction() throws Exception {
        UUID projectId = insertProject(DEMO_USER, "ACTIVE");

        MvcResult result =
                mockMvc.perform(
                                post(CONVERSATIONS_URL, projectId)
                                        .header("X-User-Id", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"Hội thoại đầu tiên\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                        .andExpect(jsonPath("$.title").value("Hội thoại đầu tiên"))
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.mainStageId").isNotEmpty())
                        .andExpect(jsonPath("$.createdAt").isNotEmpty())
                        .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                        .andReturn();
        JsonNode body =
                objectMapper.readTree(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        UUID conversationId = UUID.fromString(body.get("id").asText());
        UUID mainStageId = UUID.fromString(body.get("mainStageId").asText());

        Map<String, Object> conversation =
                jdbcTemplate.queryForMap(
                        "SELECT project_id, title, status FROM contextflow.conversations WHERE id = ?",
                        conversationId);
        assertEquals(projectId, conversation.get("project_id"));
        assertEquals("Hội thoại đầu tiên", conversation.get("title"));
        assertEquals("ACTIVE", conversation.get("status"));

        List<Map<String, Object>> stages =
                jdbcTemplate.queryForList(
                        """
                        SELECT id, project_id, parent_stage_id, stage_order, title, status
                        FROM contextflow.main_stages
                        WHERE conversation_id = ?
                        """,
                        conversationId);
        assertEquals(1, stages.size());
        Map<String, Object> mainOne = stages.getFirst();
        assertEquals(mainStageId, mainOne.get("id"));
        assertEquals(projectId, mainOne.get("project_id"));
        assertNull(mainOne.get("parent_stage_id"));
        assertEquals(1, mainOne.get("stage_order"));
        assertEquals("Main 1", mainOne.get("title"));
        assertEquals("ACTIVE", mainOne.get("status"));

        List<Map<String, Object>> epochs =
                jdbcTemplate.queryForList(
                        """
                        SELECT project_id, conversation_id, route_kind, route_id, epoch_no, status
                        FROM contextflow.continuity_epochs
                        WHERE main_stage_id = ?
                        """,
                        mainStageId);
        assertEquals(1, epochs.size());
        Map<String, Object> epoch = epochs.getFirst();
        assertEquals(projectId, epoch.get("project_id"));
        assertEquals(conversationId, epoch.get("conversation_id"));
        assertEquals("MAIN", epoch.get("route_kind"));
        assertEquals(mainStageId, epoch.get("route_id"));
        assertEquals(1L, epoch.get("epoch_no"));
        assertEquals("ACTIVE", epoch.get("status"));
    }

    @Test
    void createRejectsInvalidBodiesAndPathValues() throws Exception {
        UUID projectId = insertProject(DEMO_USER, "ACTIVE");

        for (String body :
                List.of(
                        "{\"title\":\"   \"}",
                        "{}",
                        "{\"title\":null}",
                        "{\"title\":\"Ok\",\"status\":\"ARCHIVED\"}",
                        "{\"title\":")) {
            expectError(
                    post(CONVERSATIONS_URL, projectId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    400,
                    "VALIDATION_ERROR");
        }
        expectError(
                post("/api/v1/projects/not-a-uuid/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ok\"}"),
                400,
                "VALIDATION_ERROR");
        assertEquals(0, countRows("contextflow.conversations"));
    }

    @Test
    void createEnforcesProjectOwnershipAndLifecycle() throws Exception {
        UUID otherUser = insertUser();
        UUID foreignProject = insertProject(otherUser, "ACTIVE");
        UUID deletedProject = insertProject(DEMO_USER, "DELETED");
        UUID deletingProject = insertProject(DEMO_USER, "DELETING");

        expectError(createRequest(foreignProject), 404, "RESOURCE_NOT_FOUND");
        expectError(createRequest(UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        expectError(createRequest(deletedProject), 404, "RESOURCE_NOT_FOUND");
        expectError(createRequest(deletingProject), 409, "PROJECT_DELETING");
        assertEquals(0, countRows("contextflow.conversations"));
        assertEquals(0, countRows("contextflow.main_stages"));
        assertEquals(0, countRows("contextflow.continuity_epochs"));
    }

    @Test
    void listsConversationsWithPaginationHiddenDeletingAndCurrentMainStage() throws Exception {
        UUID projectId = insertProject(DEMO_USER, "ACTIVE");
        UUID otherProjectId = insertProject(DEMO_USER, "ACTIVE");
        UUID active = insertConversation(projectId, "Đang mở", "ACTIVE", at("2026-11-01"));
        UUID archived = insertConversation(projectId, "Lưu trữ", "ARCHIVED", at("2026-11-02"));
        UUID locked = insertConversation(projectId, "Khóa", "LOCKED_READONLY", at("2026-11-03"));
        insertConversation(projectId, "Đang xóa", "DELETING", at("2026-11-04"));
        insertConversation(otherProjectId, "Dự án khác", "ACTIVE", at("2026-11-05"));
        insertStage(projectId, active, 1);
        UUID activeMainTwo = insertStage(projectId, active, 2);
        UUID archivedMainOne = insertStage(projectId, archived, 1);

        mockMvc.perform(get(CONVERSATIONS_URL, projectId).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(locked.toString()))
                .andExpect(jsonPath("$.items[0].status").value("LOCKED_READONLY"))
                .andExpect(jsonPath("$.items[0].mainStageId").value(nullValue()))
                .andExpect(jsonPath("$.items[1].id").value(archived.toString()))
                .andExpect(jsonPath("$.items[1].mainStageId").value(archivedMainOne.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get(CONVERSATIONS_URL, projectId).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(active.toString()))
                .andExpect(jsonPath("$.items[0].projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Đang mở"))
                .andExpect(jsonPath("$.items[0].mainStageId").value(activeMainTwo.toString()))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-01T00:00:00Z"))
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(get(CONVERSATIONS_URL, projectId).param("status", "ARCHIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(archived.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get(CONVERSATIONS_URL, projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void listRejectsInvalidParametersAndEnforcesProjectScope() throws Exception {
        UUID projectId = insertProject(DEMO_USER, "ACTIVE");
        for (Map.Entry<String, String> param :
                List.of(
                        Map.entry("size", "0"),
                        Map.entry("size", "101"),
                        Map.entry("size", "abc"),
                        Map.entry("page", "-1"),
                        Map.entry("page", String.valueOf(Integer.MAX_VALUE)),
                        Map.entry("status", "DELETING"),
                        Map.entry("status", "LOCKED_READONLY"),
                        Map.entry("status", "unknown"))) {
            expectError(
                    get(CONVERSATIONS_URL, projectId).param(param.getKey(), param.getValue()),
                    400,
                    "VALIDATION_ERROR");
        }

        UUID foreignProject = insertProject(insertUser(), "ACTIVE");
        expectError(get(CONVERSATIONS_URL, foreignProject), 404, "RESOURCE_NOT_FOUND");
        expectError(get(CONVERSATIONS_URL, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        expectError(
                get(CONVERSATIONS_URL, insertProject(DEMO_USER, "DELETED")),
                404,
                "RESOURCE_NOT_FOUND");
        expectError(
                get(CONVERSATIONS_URL, insertProject(DEMO_USER, "DELETING")),
                409,
                "PROJECT_DELETING");
    }

    @Test
    void patchRenamesActiveConversationAndReturnsCurrentMainStage() throws Exception {
        UUID projectId = insertProject(DEMO_USER, "ACTIVE");
        UUID conversationId =
                insertConversation(projectId, "Tiêu đề cũ", "ACTIVE", at("2026-01-01"));
        UUID mainOne = insertStage(projectId, conversationId, 1);

        mockMvc.perform(
                        patch(CONVERSATION_URL, projectId, conversationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"Tiêu đề mới\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversationId.toString()))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.title").value("Tiêu đề mới"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mainStageId").value(mainOne.toString()))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"));

        assertEquals("Tiêu đề mới", titleOf(conversationId));
        assertTrue(
                jdbcTemplate.queryForObject(
                        """
                        SELECT updated_at > '2026-01-01T00:00:00Z'::timestamptz
                        FROM contextflow.conversations
                        WHERE id = ?
                        """,
                        Boolean.class,
                        conversationId));
    }

    @Test
    void patchRejectsCrossProjectLockedStatesAndInvalidBodies() throws Exception {
        UUID projectA = insertProject(DEMO_USER, "ACTIVE");
        UUID projectB = insertProject(DEMO_USER, "ACTIVE");
        UUID conversationId = insertConversation(projectA, "Gốc", "ACTIVE", at("2026-01-01"));
        UUID foreignProject = insertProject(insertUser(), "ACTIVE");
        UUID foreignConversation =
                insertConversation(foreignProject, "Người khác", "ACTIVE", at("2026-01-01"));

        expectError(renameRequest(projectB, conversationId), 404, "RESOURCE_NOT_FOUND");
        expectError(renameRequest(projectA, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        expectError(renameRequest(foreignProject, foreignConversation), 404, "RESOURCE_NOT_FOUND");

        for (String body :
                List.of(
                        "{}",
                        "{\"title\":\"  \"}",
                        "{\"title\":null}",
                        "{\"title\":\"Mới\",\"status\":\"ARCHIVED\"}")) {
            expectError(
                    patch(CONVERSATION_URL, projectA, conversationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    400,
                    "VALIDATION_ERROR");
        }

        setConversationStatus(conversationId, "ARCHIVED");
        expectError(renameRequest(projectA, conversationId), 400, "VALIDATION_ERROR");
        setConversationStatus(conversationId, "LOCKED_READONLY");
        expectError(renameRequest(projectA, conversationId), 400, "VALIDATION_ERROR");
        setConversationStatus(conversationId, "DELETING");
        expectError(renameRequest(projectA, conversationId), 404, "RESOURCE_NOT_FOUND");

        setConversationStatus(conversationId, "ACTIVE");
        jdbcTemplate.update(
                "UPDATE contextflow.projects SET status = 'DELETING' WHERE id = ?", projectA);
        expectError(renameRequest(projectA, conversationId), 409, "PROJECT_DELETING");

        assertEquals("Gốc", titleOf(conversationId));
        assertEquals("Người khác", titleOf(foreignConversation));
    }

    private MockHttpServletRequestBuilder createRequest(UUID projectId) {
        return post(CONVERSATIONS_URL, projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Hội thoại\"}");
    }

    private MockHttpServletRequestBuilder renameRequest(UUID projectId, UUID conversationId) {
        return patch(CONVERSATION_URL, projectId, conversationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Tên đã đổi\"}");
    }

    private void expectError(MockHttpServletRequestBuilder request, int httpStatus, String code)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.correlationId").isNotEmpty());
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.users (id, email, display_name) VALUES (?, ?, 'Other')",
                userId,
                userId + "@example.test");
        return userId;
    }

    private UUID insertProject(UUID ownerUserId, String projectStatus) {
        UUID projectId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.projects (id, owner_user_id, name, status)
                VALUES (?, ?, 'Dự án', ?)
                """,
                projectId,
                ownerUserId,
                projectStatus);
        return projectId;
    }

    private UUID insertConversation(
            UUID projectId, String title, String conversationStatus, Instant timestamp) {
        UUID conversationId = UUID.randomUUID();
        OffsetDateTime time = OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.conversations (
                    id, project_id, title, status, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                conversationId,
                projectId,
                title,
                conversationStatus,
                time,
                time);
        return conversationId;
    }

    private UUID insertStage(UUID projectId, UUID conversationId, int stageOrder) {
        UUID stageId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.main_stages (
                    id, project_id, conversation_id, stage_order, title
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                stageId,
                projectId,
                conversationId,
                stageOrder,
                "Main " + stageOrder);
        return stageId;
    }

    private void setConversationStatus(UUID conversationId, String conversationStatus) {
        jdbcTemplate.update(
                "UPDATE contextflow.conversations SET status = ? WHERE id = ?",
                conversationStatus,
                conversationId);
    }

    private String titleOf(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT title FROM contextflow.conversations WHERE id = ?",
                String.class,
                conversationId);
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static Instant at(String date) {
        return Instant.parse(date + "T00:00:00Z");
    }
}

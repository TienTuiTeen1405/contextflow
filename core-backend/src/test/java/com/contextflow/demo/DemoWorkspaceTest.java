package com.contextflow.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.contextflow.identity.demo.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class DemoWorkspaceTest {
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

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoUserSeeder seeder;

    @AfterEach
    void cleanIsolatedDatabase() {
        jdbc.update("DELETE FROM contextflow.projects");
        jdbc.update("DELETE FROM contextflow.personal_skills");
        jdbc.update("DELETE FROM contextflow.users WHERE id <> ?", DemoIdentity.USER_ID);
        jdbc.update(
                """
                UPDATE contextflow.users SET display_name='Demo User',role='USER',status='ACTIVE',
                    theme_preference='SYSTEM',personal_intelligence_enabled=true WHERE id=?
                """,
                DemoIdentity.USER_ID);
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setOrigin("http://localhost:3000");
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode ok(HttpMethod method, String path, Object body, int status) {
        var response = call(method, path, body);
        assertEquals(
                status, response.getStatusCode().value(), () -> String.valueOf(response.getBody()));
        assertEquals("DEMO", response.getHeaders().getFirst("X-ContextFlow-Mode"));
        assertNotNull(response.getHeaders().getFirst("X-Correlation-Id"));
        return response.getBody();
    }

    private void error(HttpMethod method, String path, Object body, int status, String code) {
        JsonNode result = ok(method, path, body, status);
        assertEquals(code, result.path("error").path("code").asText());
        assertTrue(result.path("error").hasNonNull("correlationId"));
    }

    private UUID project() {
        return UUID.fromString(
                ok(
                                HttpMethod.POST,
                                "/api/v1/projects",
                                Map.of("name", "Demo project", "description", "Keep this"),
                                201)
                        .get("id")
                        .asText());
    }

    private String conversations(UUID project) {
        return "/api/v1/projects/" + project + "/conversations";
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void fixedUserWorksWithoutJwtAndSeederDoesNotOverwriteEdits() {
        JsonNode user = ok(HttpMethod.GET, "/api/v1/me", null, 200);
        assertEquals(DemoIdentity.USER_ID.toString(), user.get("id").asText());
        assertEquals("USER", user.get("role").asText());
        assertFalse(user.get("hasLocalPassword").asBoolean());
        ok(
                HttpMethod.PATCH,
                "/api/v1/me",
                Map.of("displayName", "Demo learner", "themePreference", "DARK"),
                200);
        seeder.run(null);
        seeder.run(null);
        JsonNode after = ok(HttpMethod.GET, "/api/v1/me", null, 200);
        assertEquals("Demo learner", after.get("displayName").asText());
        assertEquals("DARK", after.get("themePreference").asText());
        assertEquals(
                1,
                count("SELECT count(*) FROM contextflow.users WHERE id=?", DemoIdentity.USER_ID));
        assertEquals(0, count("SELECT count(*) FROM contextflow.auth_sessions"));
    }

    @Test
    void projectCreationInitializesIndexAndPaginationReflectsPersistence() {
        UUID project = project();
        JsonNode view = ok(HttpMethod.GET, "/api/v1/projects/" + project, null, 200);
        assertEquals("ACTIVE", view.get("status").asText());
        assertEquals(
                1,
                count(
                        """
                        SELECT count(*) FROM contextflow.index_generations WHERE project_id=?
                            AND status='ACTIVE' AND output_dimensionality=1536 AND embedding_model='gemini-embedding-2'
                        """,
                        project));
        JsonNode page = ok(HttpMethod.GET, "/api/v1/projects?page=0&size=1", null, 200);
        assertEquals(1, page.get("totalElements").asInt());
        assertEquals(project.toString(), page.get("items").get(0).get("id").asText());
        assertFalse(page.get("hasNext").asBoolean());
        assertEquals(
                0,
                ok(HttpMethod.GET, "/api/v1/projects?page=1&size=1", null, 200)
                        .get("items")
                        .size());
    }

    @Test
    void conversationCreationBootstrapsMainAndEpochAtomicallyAndCanRename() {
        UUID project = project();
        JsonNode created =
                ok(
                        HttpMethod.POST,
                        conversations(project),
                        Map.of("title", "First conversation"),
                        201);
        UUID conversation = UUID.fromString(created.get("id").asText());
        UUID stage = UUID.fromString(created.get("mainStageId").asText());
        assertEquals(
                1,
                count(
                        """
                        SELECT count(*) FROM contextflow.main_stages WHERE id=? AND project_id=? AND conversation_id=?
                            AND stage_order=1 AND title='Main 1' AND status='ACTIVE'
                        """,
                        stage,
                        project,
                        conversation));
        assertEquals(
                1,
                count(
                        """
                        SELECT count(*) FROM contextflow.continuity_epochs WHERE main_stage_id=? AND project_id=?
                            AND conversation_id=? AND epoch_no=1 AND status='ACTIVE' AND provider='MOCK'
                            AND route_kind='MAIN' AND route_id=? AND octet_length(compatibility_key)=32
                        """,
                        stage,
                        project,
                        conversation,
                        stage));
        ok(
                HttpMethod.PATCH,
                conversations(project) + "/" + conversation,
                Map.of("title", "Renamed"),
                200);
        JsonNode list = ok(HttpMethod.GET, conversations(project), null, 200);
        assertEquals("Renamed", list.get("items").get(0).get("title").asText());
        assertEquals(stage.toString(), list.get("items").get(0).get("mainStageId").asText());
    }

    @Test
    void patchDistinguishesAbsentNullableAndInvalidNull() {
        UUID project = project();
        String path = "/api/v1/projects/" + project;
        JsonNode renamed = ok(HttpMethod.PATCH, path, Map.of("name", "Renamed"), 200);
        assertEquals("Keep this", renamed.get("description").asText());
        var clear = new HashMap<String, Object>();
        clear.put("description", null);
        JsonNode cleared = ok(HttpMethod.PATCH, path, clear, 200);
        assertEquals("Renamed", cleared.get("name").asText());
        assertTrue(cleared.get("description") == null || cleared.get("description").isNull());
        var bad = new HashMap<String, Object>();
        bad.put("name", null);
        error(HttpMethod.PATCH, path, bad, 400, "VALIDATION_ERROR");
        error(HttpMethod.PATCH, path, Map.of(), 400, "VALIDATION_ERROR");
        error(
                HttpMethod.PATCH,
                path,
                Map.of("ownerUserId", UUID.randomUUID()),
                400,
                "VALIDATION_ERROR");
    }

    @Test
    void personalSkillsUseDefaultsPreservePatchFieldsAndDeleteOnlyOwnSkill() {
        JsonNode created =
                ok(
                        HttpMethod.POST,
                        "/api/v1/me/personal-skills",
                        Map.of(
                                "name",
                                "Vietnamese",
                                "content",
                                "Trả lời tiếng Việt",
                                "description",
                                "Keep"),
                        201);
        assertEquals(100, created.get("priority").asInt());
        assertTrue(created.get("isEnabled").asBoolean());
        String path = "/api/v1/me/personal-skills/" + created.get("id").asText();
        var patch = new HashMap<String, Object>();
        patch.put("priority", 0);
        patch.put("isEnabled", false);
        patch.put("description", null);
        JsonNode updated = ok(HttpMethod.PATCH, path, patch, 200);
        assertEquals("Vietnamese", updated.get("name").asText());
        assertEquals(0, updated.get("priority").asInt());
        assertFalse(updated.get("isEnabled").asBoolean());
        JsonNode intelligence = ok(HttpMethod.GET, "/api/v1/me/personal-intelligence", null, 200);
        assertEquals(1, intelligence.get("personalSkills").size());
        ok(HttpMethod.DELETE, path, null, 204);
        assertEquals(
                0,
                ok(HttpMethod.GET, "/api/v1/me/personal-intelligence", null, 200)
                        .get("personalSkills")
                        .size());
        error(HttpMethod.DELETE, path, null, 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void validationAndBlockedRoutesStayWithinDemoScope() {
        error(HttpMethod.POST, "/api/v1/projects", Map.of("name", "   "), 400, "VALIDATION_ERROR");
        error(HttpMethod.GET, "/api/v1/projects?size=101", null, 400, "VALIDATION_ERROR");
        error(HttpMethod.GET, "/api/v1/projects?page=-1", null, 400, "VALIDATION_ERROR");
        error(HttpMethod.GET, "/api/v1/projects/not-a-uuid", null, 400, "VALIDATION_ERROR");
        error(HttpMethod.PATCH, "/api/v1/me", Map.of("role", "ADMIN"), 400, "VALIDATION_ERROR");
        error(
                HttpMethod.POST,
                "/api/v1/me/personal-skills",
                Map.of("name", "Test", "content", "", "priority", -1),
                400,
                "VALIDATION_ERROR");
        error(
                HttpMethod.POST,
                "/api/v1/projects",
                Map.of("name", "No copy", "ruleTemplateIds", List.of(UUID.randomUUID())),
                400,
                "VALIDATION_ERROR");
        var nullPriority = new HashMap<String, Object>();
        nullPriority.put("name", "Null priority");
        nullPriority.put("content", "Content");
        nullPriority.put("priority", null);
        error(HttpMethod.POST, "/api/v1/me/personal-skills", nullPriority, 400, "VALIDATION_ERROR");
        assertEquals(0, count("SELECT count(*) FROM contextflow.projects"));
        error(HttpMethod.GET, "/api/v1/admin/users", null, 403, "FORBIDDEN");
        error(HttpMethod.GET, "/api/v1/me/credential", null, 403, "FORBIDDEN");
        error(HttpMethod.POST, "/api/v1/auth/login", Map.of(), 403, "FORBIDDEN");
        error(HttpMethod.POST, "/api/v1/ai/chat-requests", Map.of(), 403, "FORBIDDEN");
    }

    @Test
    void forgedIdentityDoesNotBypassOwnership() {
        UUID foreignUser = UUID.randomUUID(),
                foreignProject = UUID.randomUUID(),
                foreignSkill = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO contextflow.users(id,email,display_name) VALUES (?,?,?)",
                foreignUser,
                "other@example.test",
                "Other");
        jdbc.update(
                "INSERT INTO contextflow.projects(id,owner_user_id,name) VALUES (?,?,?)",
                foreignProject,
                foreignUser,
                "Other");
        jdbc.update(
                "INSERT INTO contextflow.personal_skills(id,user_id,name,content) VALUES (?,?,?,?)",
                foreignSkill,
                foreignUser,
                "Other",
                "Content");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", foreignUser.toString());
        headers.set("Authorization", "Bearer fake-admin-token");
        var response =
                http.exchange(
                        "/api/v1/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertEquals(DemoIdentity.USER_ID.toString(), response.getBody().get("id").asText());
        error(
                HttpMethod.GET,
                "/api/v1/projects/" + foreignProject,
                null,
                404,
                "RESOURCE_NOT_FOUND");
        error(
                HttpMethod.POST,
                conversations(foreignProject),
                Map.of("title", "Forbidden"),
                404,
                "RESOURCE_NOT_FOUND");
        error(
                HttpMethod.PATCH,
                "/api/v1/me/personal-skills/" + foreignSkill,
                Map.of("name", "Changed"),
                404,
                "RESOURCE_NOT_FOUND");
        error(
                HttpMethod.DELETE,
                "/api/v1/me/personal-skills/" + foreignSkill,
                null,
                404,
                "RESOURCE_NOT_FOUND");
        assertEquals(
                "Other",
                jdbc.queryForObject(
                        "SELECT name FROM contextflow.personal_skills WHERE id=?",
                        String.class,
                        foreignSkill));
        assertEquals(0, ok(HttpMethod.GET, "/api/v1/projects", null, 200).get("items").size());
    }

    @Test
    void crossProjectConversationAndDeletingProjectsAreRejected() {
        UUID first = project(), second = project();
        JsonNode conversation =
                ok(HttpMethod.POST, conversations(first), Map.of("title", "Private route"), 201);
        error(
                HttpMethod.PATCH,
                conversations(second) + "/" + conversation.get("id").asText(),
                Map.of("title", "Wrong scope"),
                404,
                "RESOURCE_NOT_FOUND");
        jdbc.update("UPDATE contextflow.projects SET status='DELETING' WHERE id=?", first);
        error(HttpMethod.GET, "/api/v1/projects/" + first, null, 409, "PROJECT_DELETING");
        error(HttpMethod.GET, conversations(first), null, 409, "PROJECT_DELETING");
        error(
                HttpMethod.POST,
                conversations(first),
                Map.of("title", "No new child"),
                409,
                "PROJECT_DELETING");
        error(
                HttpMethod.PATCH,
                "/api/v1/projects/" + first,
                Map.of("name", "No edit"),
                409,
                "PROJECT_DELETING");
    }

    @Test
    void disabledDemoAccountIsNotSilentlyReenabled() {
        jdbc.update(
                "UPDATE contextflow.users SET status='DISABLED' WHERE id=?", DemoIdentity.USER_ID);
        error(HttpMethod.GET, "/api/v1/me", null, 403, "ACCOUNT_DISABLED");
        error(
                HttpMethod.POST,
                "/api/v1/projects",
                Map.of("name", "Blocked"),
                403,
                "ACCOUNT_DISABLED");
        assertThrows(IllegalStateException.class, () -> seeder.run(null));
        assertEquals(
                "DISABLED",
                jdbc.queryForObject(
                        "SELECT status FROM contextflow.users WHERE id=?",
                        String.class,
                        DemoIdentity.USER_ID));
    }
}

package com.contextflow.credential;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.contextflow.ai.service.ContinuityEpochBootstrap;
import com.contextflow.common.api.ApiProblem;
import com.contextflow.common.api.ErrorCode;
import com.contextflow.credential.client.AiCredentialVerifyClient;
import com.contextflow.credential.crypto.EnvelopeCryptoService;
import com.contextflow.credential.dto.CredentialStatus;
import com.contextflow.credential.dto.CredentialVerifyResponse;
import com.contextflow.credential.dto.VerifyErrorCode;
import com.contextflow.credential.repository.AiCredentialRepository;
import com.contextflow.identity.demo.LocalDemoIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties")
@AutoConfigureMockMvc
@ActiveProfiles("local-demo")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
@Testcontainers
class UserCredentialIntegrationTest {
    private static final String CREDENTIAL = "/api/v1/me/credential";
    private static final String ACKNOWLEDGEMENTS = "/api/v1/me/privacy-acknowledgements";
    private static final String MOCK_KEY = "mock-provider-credential";
    private static final String BAD_KEY = "synthetic-invalid-credential";
    private static final String BASE64_KEK = Base64.getEncoder().encodeToString(new byte[32]);
    private static final UUID USER_ID = LocalDemoIdentity.USER_ID;
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final Instant CHECKED_AT = Instant.parse("2026-11-26T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.1-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", POSTGRES::getUsername);
        properties.add("spring.flyway.password", POSTGRES::getPassword);
        properties.add("CF_CREDENTIAL_KEK_V1", () -> BASE64_KEK);
    }

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final AiCredentialRepository credentials;
    private final EnvelopeCryptoService crypto;
    private final ContinuityEpochBootstrap continuityBootstrap;

    @MockitoBean private AiCredentialVerifyClient verifyClient;

    @BeforeEach
    void prepareIsolatedDatabaseAndVerifier() {
        jdbc.update("DELETE FROM contextflow.projects");
        jdbc.update("DELETE FROM contextflow.ai_credentials");
        jdbc.update("DELETE FROM contextflow.users WHERE id = ?", OTHER_USER_ID);
        jdbc.update("UPDATE contextflow.users SET status = 'ACTIVE' WHERE id = ?", USER_ID);
        jdbc.update(
                """
                INSERT INTO contextflow.users (id, email, display_name)
                VALUES (?, 'other-credential-test@contextflow.local', 'Other Test Account')
                """,
                OTHER_USER_ID);

        when(verifyClient.verify(anyString(), any(UUID.class)))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    boolean valid = MOCK_KEY.equals(invocation.getArgument(0));
                    return new CredentialVerifyResponse(
                            valid,
                            CHECKED_AT,
                            valid ? null : VerifyErrorCode.INVALID_API_KEY,
                            "NOT_TESTED");
                });
    }

    @Test
    void verifiesOutsideTransactionAndPersistsOnlyEncryptedKeyOnPut() throws Exception {
        assertThat(body(mvc.perform(get(CREDENTIAL)).andExpect(status().isOk()).andReturn()))
                .isEqualTo(
                        json.readTree(
                                """
                                {"configured": false, "disclosureRequired": false}
                                """));

        MvcResult verified =
                mvc.perform(
                                post(CREDENTIAL + "/verify")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.createObjectNode()
                                                        .put("apiKey", MOCK_KEY)
                                                        .toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.valid").value(true))
                        .andExpect(jsonPath("$.capabilityStatus").value("NOT_TESTED"))
                        .andReturn();
        verify(verifyClient)
                .verify(
                        eq(MOCK_KEY),
                        eq(UUID.fromString(verified.getResponse().getHeader("X-Correlation-Id"))));
        assertThat(credentialCount()).isZero();

        var installed = putKey(MOCK_KEY, "FREE", "v1");
        assertThat(installed.get("credentialVersion").asLong()).isEqualTo(1);
        assertThat(installed.get("maskedLabel").asText()).isEqualTo("****tial");
        assertThat(installed.get("disclosureRequired").asBoolean()).isTrue();
        assertThat(installed.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "configured",
                        "credentialId",
                        "credentialVersion",
                        "status",
                        "maskedLabel",
                        "declaredTier",
                        "googleCloudProjectRef",
                        "verifiedAt",
                        "disclosureRequired");

        var saved = credentials.findUserCredential(USER_ID, CredentialStatus.ACTIVE).orElseThrow();
        assertThat(saved.getEncryptedSecret()).isNotEqualTo(MOCK_KEY.getBytes(UTF_8));
        assertThat(
                        crypto.decrypt(
                                USER_ID,
                                saved.getId(),
                                saved.getKekVersion(),
                                saved.getEncryptedSecret(),
                                saved.getWrappedDek()))
                .isEqualTo(MOCK_KEY.getBytes(UTF_8));
        assertThat(receiptCount("CREDENTIAL_SETUP")).isEqualTo(1);

        MvcResult status =
                mvc.perform(get(CREDENTIAL).header("X-User-Id", OTHER_USER_ID.toString()))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(body(status)).isEqualTo(installed);
        assertThat(status.getResponse().getContentAsString()).doesNotContain(MOCK_KEY);
    }

    @Test
    void validationAndVerifyFailuresPreserveTheActiveCredential() throws Exception {
        var active = putKey(MOCK_KEY, "PAID", null);
        assertThat(active.get("disclosureRequired").asBoolean()).isFalse();

        mvc.perform(
                        post(CREDENTIAL + "/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.createObjectNode().put("apiKey", BAD_KEY).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_API_KEY"));

        var rejected =
                mvc.perform(putRequest(BAD_KEY, "PAID", null))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.error.code").value("USER_CREDENTIAL_UNAVAILABLE"))
                        .andReturn();
        assertThat(rejected.getResponse().getContentAsString()).doesNotContain(BAD_KEY);

        mvc.perform(putRequest(MOCK_KEY, "UNKNOWN", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DISCLOSURE_REQUIRED"));
        mvc.perform(
                        put(CREDENTIAL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.createObjectNode()
                                                .put("apiKey", MOCK_KEY)
                                                .put("declaredTier", 0)
                                                .toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        doThrow(
                        new ApiProblem(
                                ErrorCode.PROVIDER_UNAVAILABLE,
                                "Credential verification is unavailable."))
                .when(verifyClient)
                .verify(eq(MOCK_KEY), any(UUID.class));
        mvc.perform(putRequest(MOCK_KEY, "PAID", null))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_UNAVAILABLE"));
        assertThat(credentialCount()).isEqualTo(1);
        assertThat(body(mvc.perform(get(CREDENTIAL)).andReturn())).isEqualTo(active);
    }

    @Test
    void concurrentPutsAllocateDistinctVersionsWithOnlyOneActive() throws Exception {
        var verifyStarted = new CountDownLatch(2);
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            verifyStarted.countDown();
                            assertThat(verifyStarted.await(10, TimeUnit.SECONDS)).isTrue();
                            return new CredentialVerifyResponse(
                                    true, CHECKED_AT, null, "NOT_TESTED");
                        })
                .when(verifyClient)
                .verify(eq(MOCK_KEY), any(UUID.class));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> putKey(MOCK_KEY, "PAID", null));
            var right = executor.submit(() -> putKey(MOCK_KEY, "PAID", null));
            assertThat(
                            List.of(
                                    left.get(20, TimeUnit.SECONDS)
                                            .get("credentialVersion")
                                            .asLong(),
                                    right.get(20, TimeUnit.SECONDS)
                                            .get("credentialVersion")
                                            .asLong()))
                    .containsExactlyInAnyOrder(1L, 2L);
        }
        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT status FROM contextflow.ai_credentials WHERE owner_user_id = ?
                                ORDER BY credential_version
                                """,
                                String.class,
                                USER_ID))
                .containsExactly("ROTATED", "ACTIVE");
    }

    @Test
    void acknowledgementReplaysTheSameReceiptAndIsBoundToCurrentVersion() throws Exception {
        var installed = putKey(MOCK_KEY, "FREE", "v1");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> acknowledge(installed, "v1"));
            var right = executor.submit(() -> acknowledge(installed, "v1"));
            MvcResult first = left.get(20, TimeUnit.SECONDS);
            MvcResult second = right.get(20, TimeUnit.SECONDS);
            assertThat(List.of(first.getResponse().getStatus(), second.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 201);
            assertThat(body(first)).isEqualTo(body(second));
            assertThat(body(first).get("effectiveTier").asText()).isEqualTo("FREE");
        }
        assertThat(receiptCount("WORKSPACE_OPERATION")).isEqualTo(1);
        mvc.perform(get(CREDENTIAL)).andExpect(jsonPath("$.disclosureRequired").value(false));

        var rotated = putKey(MOCK_KEY, "UNKNOWN", "v1");
        assertThat(rotated.get("disclosureRequired").asBoolean()).isTrue();
        assertThat(acknowledge(installed, "v1").getResponse().getStatus()).isEqualTo(404);
        var staleDisclosure = acknowledge(rotated, "v0");
        assertThat(staleDisclosure.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(staleDisclosure).at("/error/code").asText())
                .isEqualTo("DISCLOSURE_REQUIRED");

        var paid = putKey(MOCK_KEY, "PAID", null);
        var paidAck = acknowledge(paid, "v1");
        assertThat(paidAck.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(paidAck).at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void rechecksDisabledAccountAfterVerifyBeforeWriting() throws Exception {
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            jdbc.update(
                                    "UPDATE contextflow.users SET status = 'DISABLED' WHERE id = ?",
                                    USER_ID);
                            return new CredentialVerifyResponse(
                                    true, CHECKED_AT, null, "NOT_TESTED");
                        })
                .when(verifyClient)
                .verify(eq(MOCK_KEY), any(UUID.class));
        mvc.perform(putRequest(MOCK_KEY, "PAID", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
        mvc.perform(get(CREDENTIAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
        assertThat(credentialCount()).isZero();
    }

    @Test
    void removalClearsBlobsAndCancelsOnlyOpenUserRequests() throws Exception {
        var installed = putKey(MOCK_KEY, "PAID", null);
        UUID credentialId = UUID.fromString(installed.get("credentialId").asText());
        var accepted = createRequest(USER_ID, "ACCEPTED");
        var inProgress = createRequest(USER_ID, "IN_PROGRESS");
        var completed = createRequest(USER_ID, "COMPLETED");
        var independent = createRequest(USER_ID, "COMPLETED");
        var other = createRequest(OTHER_USER_ID, "IN_PROGRESS");
        jdbc.update(
                """
                INSERT INTO contextflow.ai_usage_records (
                    provider_call_id, correlation_id, contextflow_user_id, contextflow_project_id,
                    task_type, provider, api_interface, model, credential_id, credential_version,
                    owner_scope, credential_tier, attempt_number, occurred_at, execution_status, usage_status
                )
                VALUES (?, ?, ?, ?, 'CHAT_MAIN', 'MOCK', 'INTERACTIONS', 'mock', ?, 1,
                        'USER', 'PAID', 1, now(), 'COMPLETED', 'UNAVAILABLE')
                """,
                UUID.randomUUID(),
                completed.correlationId,
                USER_ID,
                completed.projectId,
                credentialId);

        mvc.perform(delete(CREDENTIAL)).andExpect(status().isNoContent());
        mvc.perform(delete(CREDENTIAL)).andExpect(status().isNoContent());
        mvc.perform(get(CREDENTIAL)).andExpect(jsonPath("$.configured").value(false));
        var missing = acknowledge(installed, "v1");
        assertThat(missing.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(missing).at("/error/code").asText())
                .isEqualTo("USER_CREDENTIAL_UNAVAILABLE");

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT status = 'REMOVED' AND octet_length(encrypted_secret) = 0
                                    AND octet_length(wrapped_dek) = 0 FROM contextflow.ai_credentials WHERE id = ?
                                """,
                                Boolean.class,
                                credentialId))
                .isTrue();

        for (var cancelled : List.of(accepted, inProgress)) {
            assertThat(
                            jdbc.queryForObject(
                                    """
                                    SELECT status = 'CANCELLED' AND finished_at IS NOT NULL AND last_event_id = 2
                                        AND queued_input IS NULL AND worker_token IS NULL AND lease_expires_at IS NULL
                                    FROM contextflow.ai_requests WHERE id = ?
                                    """,
                                    Boolean.class,
                                    cancelled.id))
                    .isTrue();
            assertThat(
                            jdbc.queryForList(
                                    """
                                    SELECT event_type FROM contextflow.ai_request_events WHERE request_id = ?
                                    ORDER BY event_id
                                    """,
                                    String.class,
                                    cancelled.id))
                    .containsExactly("request.accepted", "request.cancelled");
            assertThat(
                            jdbc.queryForObject(
                                    """
                                    SELECT payload ->> 'reason' FROM contextflow.ai_request_events
                                    WHERE request_id = ? AND event_id = 2
                                    """,
                                    String.class,
                                    cancelled.id))
                    .isEqualTo("CREDENTIAL_REMOVED");
        }
        assertThat(requestStatus(completed.id)).isEqualTo("COMPLETED");
        assertThat(requestStatus(other.id)).isEqualTo("IN_PROGRESS");
        assertThat(epochStatus(accepted.epochId)).isEqualTo("INVALIDATED");
        assertThat(epochStatus(inProgress.epochId)).isEqualTo("INVALIDATED");
        assertThat(epochStatus(completed.epochId)).isEqualTo("INVALIDATED");
        assertThat(epochStatus(independent.epochId)).isEqualTo("ACTIVE");
        assertThat(epochStatus(other.epochId)).isEqualTo("ACTIVE");
        assertThat(putKey(MOCK_KEY, "PAID", null).get("credentialVersion").asLong()).isEqualTo(2);
    }

    private JsonNode putKey(String apiKey, String tier, String disclosureVersion) throws Exception {
        var result =
                mvc.perform(putRequest(apiKey, tier, disclosureVersion))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(apiKey, "encryptedSecret", "wrappedDek", "kekVersion");
        return body(result);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putRequest(
            String apiKey, String tier, String disclosureVersion) {
        var request =
                json.createObjectNode()
                        .put("apiKey", apiKey)
                        .put("declaredTier", tier)
                        .put("googleCloudProjectRef", "contextflow-sandbox");
        if (disclosureVersion != null) {
            request.put("disclosureVersionAcknowledged", disclosureVersion);
        }
        return put(CREDENTIAL).contentType(MediaType.APPLICATION_JSON).content(request.toString());
    }

    private MvcResult acknowledge(JsonNode credential, String disclosureVersion) throws Exception {
        return mvc.perform(
                        post(ACKNOWLEDGEMENTS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.createObjectNode()
                                                .put(
                                                        "credentialId",
                                                        credential.get("credentialId").asText())
                                                .put(
                                                        "credentialVersion",
                                                        credential
                                                                .get("credentialVersion")
                                                                .asLong())
                                                .put("disclosureVersion", disclosureVersion)
                                                .put("scope", "WORKSPACE_OPERATION")
                                                .toString()))
                .andReturn();
    }

    private long credentialCount() {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM contextflow.ai_credentials WHERE owner_user_id = ?
                """,
                Long.class,
                USER_ID);
    }

    private long receiptCount(String scope) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM contextflow.privacy_disclosure_acknowledgements
                WHERE actor_user_id = ? AND scope = ?
                """,
                Long.class,
                USER_ID,
                scope);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private String requestStatus(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM contextflow.ai_requests WHERE id = ?", String.class, id);
    }

    private String epochStatus(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM contextflow.continuity_epochs WHERE id = ?", String.class, id);
    }

    private ChatFixture createRequest(UUID userId, String status) {
        UUID project = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID stage = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO contextflow.projects (id, owner_user_id, name) VALUES (?, ?, 'Project')",
                project,
                userId);
        jdbc.update(
                "INSERT INTO contextflow.conversations (id, project_id, title) VALUES (?, ?, 'Conversation')",
                conversation,
                project);
        jdbc.update(
                """
                INSERT INTO contextflow.main_stages (id, project_id, conversation_id, stage_order, title)
                VALUES (?, ?, ?, 1, 'Main 1')
                """,
                stage,
                project,
                conversation);
        UUID epoch = continuityBootstrap.initializeForMainStage(project, conversation, stage);
        boolean inProgress = "IN_PROGRESS".equals(status);
        boolean open = inProgress || "ACCEPTED".equals(status);
        jdbc.update(
                """
                INSERT INTO contextflow.ai_requests
                    (id, user_id, project_id, conversation_id, main_stage_id, epoch_id, idempotency_key,
                     payload_hash, correlation_id, status, queued_input, worker_token, lease_expires_at,
                     finished_at, last_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN '{}'::jsonb END, ?,
                        CASE WHEN ? THEN now() + interval '30 seconds' END,
                        CASE WHEN ? THEN now() END, 1)
                """,
                request,
                userId,
                project,
                conversation,
                stage,
                epoch,
                request.toString(),
                new byte[32],
                correlation,
                status,
                open,
                inProgress ? UUID.randomUUID() : null,
                inProgress,
                !open);
        jdbc.update(
                """
                INSERT INTO contextflow.ai_request_events (request_id, event_id, event_type, payload)
                VALUES (?, 1, 'request.accepted', '{}'::jsonb)
                """,
                request);
        return new ChatFixture(request, project, epoch, correlation);
    }

    @AllArgsConstructor
    private static class ChatFixture {
        private final UUID id;
        private final UUID projectId;
        private final UUID epochId;
        private final UUID correlationId;
    }
}

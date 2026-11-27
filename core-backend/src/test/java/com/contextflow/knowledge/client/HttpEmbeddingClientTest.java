package com.contextflow.knowledge.client;

import static org.junit.jupiter.api.Assertions.*;

import com.contextflow.common.config.AiServiceProperties;
import com.contextflow.knowledge.dto.EmbeddingBatchRequest;
import com.contextflow.knowledge.dto.EmbeddingBatchResponse;
import com.contextflow.knowledge.dto.ProviderErrorCategory;
import com.contextflow.knowledge.worker.KnowledgeOutboxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HttpEmbeddingClientTest {
    private static final String SERVICE_TOKEN = "internal-service-token-at-least-24-chars";
    private static final String PROVIDER_KEY = "AIzaSyHttpClientRealServerTestKey123";

    private HttpServer server;
    private ObjectMapper objectMapper;
    private HttpEmbeddingClient client;
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("");
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private final AtomicReference<String> capturedCred = new AtomicReference<>();
    private final AtomicReference<String> capturedRequestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/ai/embeddings",
                exchange -> {
                    capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    capturedCred.set(
                            exchange.getRequestHeaders().getFirst("X-Provider-Credential"));
                    capturedRequestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] payload = responseBody.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(responseStatus.get(), payload.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(payload);
                    }
                });
        server.start();

        AiServiceProperties aiProps = new AiServiceProperties();
        aiProps.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

        KnowledgeOutboxProperties outboxProps = new KnowledgeOutboxProperties();
        outboxProps.setInternalServiceToken(SERVICE_TOKEN);
        outboxProps.setPlatformEmbeddingCredential(PROVIDER_KEY);

        client = new HttpEmbeddingClient(aiProps, outboxProps, objectMapper, new MockEnvironment());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsSnakeCaseWirePayloadAndAuthHeadersAndParses1536dVectors() throws Exception {
        UUID corrId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        String vecJson = objectMapper.writeValueAsString(Collections.nCopies(1536, 0.25));
        responseStatus.set(200);
        responseBody.set(
                """
                {
                  "correlation_id": "%s",
                  "provider_call_id": "%s",
                  "dispatched": true,
                  "occurred_at": "2026-11-28T00:00:00Z",
                  "completed_at": "2026-11-28T00:00:01Z",
                  "model": "gemini-embedding-2",
                  "provider_request_id": null,
                  "vectors": [%s, %s],
                  "usage_status": "PARTIAL",
                  "usage": {"input_tokens": 12, "output_tokens": null, "total_tokens": null, "cached_tokens": null, "reasoning_tokens": null}
                }
                """
                        .formatted(corrId, callId, vecJson, vecJson));

        EmbeddingBatchResponse res =
                client.embedBatch(
                        EmbeddingBatchRequest.builder()
                                .correlationId(corrId)
                                .providerCallId(callId)
                                .taskType("KB_CHUNK_EMBEDDING")
                                .model("gemini-embedding-2")
                                .outputDimensionality(1536)
                                .texts(List.of("chunk 1", "chunk 2"))
                                .build(),
                        PROVIDER_KEY);

        assertEquals("Bearer " + SERVICE_TOKEN, capturedAuth.get());
        assertEquals(PROVIDER_KEY, capturedCred.get());
        JsonNode sent = objectMapper.readTree(capturedRequestBody.get());
        assertEquals("KB_CHUNK_EMBEDDING", sent.get("task_type").asText());
        assertEquals(1536, sent.get("output_dimensionality").asInt());
        assertEquals(2, sent.get("texts").size());

        assertEquals(2, res.getVectors().size());
        assertEquals(1536, res.getVectors().getFirst().size());
        assertEquals("PARTIAL", res.getUsageStatus());
    }

    @Test
    void parsesNormalized502RateLimitErrorEnvelopeFromAiService() {
        responseStatus.set(502);
        responseBody.set(
                """
                {
                  "error": {
                    "correlation_id": "%s",
                    "provider_call_id": "%s",
                    "dispatched": true,
                    "occurred_at": "2026-11-28T00:00:00Z",
                    "completed_at": "2026-11-28T00:00:01Z",
                    "error_category": "RATE_LIMIT_RETRYABLE",
                    "error_code": "PROVIDER_RATE_LIMITED",
                    "retryable": true,
                    "retry_after_seconds": 12
                  }
                }
                """
                        .formatted(UUID.randomUUID(), UUID.randomUUID()));

        EmbeddingClientException ex =
                assertThrows(
                        EmbeddingClientException.class,
                        () ->
                                client.embedBatch(
                                        EmbeddingBatchRequest.builder()
                                                .correlationId(UUID.randomUUID())
                                                .providerCallId(UUID.randomUUID())
                                                .taskType("KB_CHUNK_EMBEDDING")
                                                .model("gemini-embedding-2")
                                                .outputDimensionality(1536)
                                                .texts(List.of("chunk 1"))
                                                .build(),
                                        PROVIDER_KEY));

        assertEquals(ProviderErrorCategory.RATE_LIMIT_RETRYABLE, ex.getErrorCategory());
        assertEquals("PROVIDER_RATE_LIMITED", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        assertEquals(12, ex.getRetryAfterSeconds());
    }

    @Test
    void rejectsInvalidVectorDimensionAndDoesNotLeakSecretsInPropertiesToString() {
        KnowledgeOutboxProperties props = new KnowledgeOutboxProperties();
        props.setInternalServiceToken(SERVICE_TOKEN);
        props.setPlatformEmbeddingCredential(PROVIDER_KEY);
        assertFalse(props.toString().contains(SERVICE_TOKEN));
        assertFalse(props.toString().contains(PROVIDER_KEY));

        responseStatus.set(200);
        responseBody.set(
                """
                {
                  "correlation_id": "%s",
                  "provider_call_id": "%s",
                  "dispatched": true,
                  "occurred_at": "2026-11-28T00:00:00Z",
                  "completed_at": "2026-11-28T00:00:01Z",
                  "model": "gemini-embedding-2",
                  "provider_request_id": null,
                  "vectors": [[0.1, 0.2]],
                  "usage_status": "UNAVAILABLE"
                }
                """
                        .formatted(UUID.randomUUID(), UUID.randomUUID()));

        EmbeddingClientException ex =
                assertThrows(
                        EmbeddingClientException.class,
                        () ->
                                client.embedBatch(
                                        EmbeddingBatchRequest.builder()
                                                .correlationId(UUID.randomUUID())
                                                .providerCallId(UUID.randomUUID())
                                                .taskType("KB_CHUNK_EMBEDDING")
                                                .model("gemini-embedding-2")
                                                .outputDimensionality(1536)
                                                .texts(List.of("chunk 1"))
                                                .build(),
                                        PROVIDER_KEY));

        assertEquals(ProviderErrorCategory.PERMANENT_INPUT_ERROR, ex.getErrorCategory());
        assertEquals("INVALID_ADAPTER_OUTPUT", ex.getErrorCode());
    }
}

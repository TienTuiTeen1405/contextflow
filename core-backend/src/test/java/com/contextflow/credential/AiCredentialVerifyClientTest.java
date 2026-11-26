package com.contextflow.credential;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

import com.contextflow.common.api.ApiProblem;
import com.contextflow.common.api.ErrorCode;
import com.contextflow.common.config.AiServiceProperties;
import com.contextflow.credential.client.AiCredentialVerifyClient;
import com.contextflow.credential.dto.VerifyErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

class AiCredentialVerifyClientTest {
    private static final String VERIFY_URL =
            "http://127.0.0.1:18000/internal/v1/ai/credentials/verify";
    private static final String TOKEN = "synthetic-service-token-for-test";
    private static final String SENTINEL = "mock-provider-credential";
    private static final Instant CHECKED_AT = Instant.parse("2026-11-26T00:00:00Z");

    @Test
    void sendsOnlyTraceIdsAndReturnsConclusiveMetadata() {
        ObjectMapper json = new ObjectMapper();
        UUID correlationId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        AiCredentialVerifyClient client =
                client((request, body, execution) -> {
                    attempts.incrementAndGet();
                    assertThat(request.getURI()).isEqualTo(URI.create(VERIFY_URL));
                    assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
                    assertThat(request.getHeaders().getFirst("Authorization"))
                            .isEqualTo("Bearer " + TOKEN);
                    String candidate = request.getHeaders().getFirst("X-Provider-Credential");
                    assertThat(candidate).isIn(SENTINEL, "synthetic-invalid-key");

                    var payload = json.readTree(body);
                    assertThat(payload.fieldNames())
                            .toIterable()
                            .containsExactlyInAnyOrder("correlation_id", "provider_call_id");
                    assertThat(payload.get("correlation_id").asText())
                            .isEqualTo(correlationId.toString());
                    UUID providerCallId = UUID.fromString(payload.get("provider_call_id").asText());
                    boolean valid = SENTINEL.equals(candidate);
                    String result =
                            """
                            {
                              "correlation_id": "%s",
                              "provider_call_id": "%s",
                              "valid": %s,
                              "checked_at": "%s",
                              "error_code": %s,
                              "capability_status": "NOT_TESTED"
                            }
                            """
                                    .formatted(
                                            correlationId,
                                            providerCallId,
                                            valid,
                                            CHECKED_AT,
                                            valid ? "null" : "\"INVALID_API_KEY\"");
                    return response(HttpStatus.OK, result);
                });

        var valid = client.verify(SENTINEL, correlationId);
        assertThat(valid.isValid()).isTrue();
        assertThat(valid.getCheckedAt()).isEqualTo(CHECKED_AT);
        assertThat(valid.getErrorCode()).isNull();
        assertThat(valid.getCapabilityStatus()).isEqualTo("NOT_TESTED");

        var invalid = client.verify("synthetic-invalid-key", correlationId);
        assertThat(invalid.isValid()).isFalse();
        assertThat(invalid.getErrorCode()).isEqualTo(VerifyErrorCode.INVALID_API_KEY);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void sanitizesTransportAndProtocolFailuresWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        AiCredentialVerifyClient offline =
                client((request, body, execution) -> {
                    attempts.incrementAndGet();
                    throw new IOException(SENTINEL);
                });
        assertUnavailable(() -> offline.verify(SENTINEL, UUID.randomUUID()));
        assertThat(attempts.get()).isEqualTo(1);

        AiCredentialVerifyClient malformed =
                client((request, body, execution) -> response(HttpStatus.OK, "{}"));
        assertUnavailable(() -> malformed.verify(SENTINEL, UUID.randomUUID()));

        AiCredentialVerifyClient rejected =
                client((request, body, execution) -> response(HttpStatus.BAD_GATEWAY, SENTINEL));
        assertUnavailable(() -> rejected.verify(SENTINEL, UUID.randomUUID()));
    }

    @Test
    void callsAnHttp11ServerWithoutRequestingAProtocolUpgrade() throws IOException {
        ObjectMapper json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/internal/v1/ai/credentials/verify",
                    exchange -> {
                        try (exchange) {
                            if (exchange.getRequestHeaders().containsKey("Upgrade")) {
                                exchange.sendResponseHeaders(400, -1);
                                return;
                            }
                            var trace = (ObjectNode) json.readTree(exchange.getRequestBody());
                            byte[] body =
                                    json.writeValueAsBytes(
                                            trace.put("valid", true)
                                                    .put("checked_at", CHECKED_AT.toString())
                                                    .putNull("error_code")
                                                    .put("capability_status", "NOT_TESTED"));
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                        }
                    });
            server.start();
            var properties = new AiServiceProperties();
            properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            var client =
                    new AiCredentialVerifyClient(
                            properties,
                            new MockEnvironment().withProperty("CF_INTERNAL_SERVICE_TOKEN", TOKEN),
                            RestClient.builder());
            assertThat(client.verify(SENTINEL, UUID.randomUUID()).isValid()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsHeaderControlCharactersBeforeSending() {
        AiCredentialVerifyClient client =
                client((request, body, execution) -> {
                    throw new AssertionError("Invalid credential reached HTTP transport.");
                });
        assertThatThrownBy(() -> client.verify("mock\r\ninvalid", UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ApiProblem.class,
                        problem ->
                                assertThat(problem.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private static AiCredentialVerifyClient client(ClientHttpRequestInterceptor interceptor) {
        AiServiceProperties properties = new AiServiceProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:18000"));
        var environment = new MockEnvironment().withProperty("CF_INTERNAL_SERVICE_TOKEN", TOKEN);
        return new AiCredentialVerifyClient(
                properties, environment, RestClient.builder().requestInterceptor(interceptor));
    }

    private static MockClientHttpResponse response(HttpStatus status, String body) {
        var response = new MockClientHttpResponse(body.getBytes(UTF_8), status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiProblem.class,
                        problem -> {
                            assertThat(problem.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
                            assertThat(problem.getMessage()).doesNotContain(SENTINEL, TOKEN);
                            assertThat(problem.getCause()).isNull();
                        });
    }
}

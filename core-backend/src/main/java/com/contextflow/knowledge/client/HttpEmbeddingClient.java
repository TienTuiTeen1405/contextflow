package com.contextflow.knowledge.client;

import com.contextflow.common.config.AiServiceProperties;
import com.contextflow.knowledge.dto.EmbeddingBatchRequest;
import com.contextflow.knowledge.dto.EmbeddingBatchResponse;
import com.contextflow.knowledge.dto.EmbeddingErrorEnvelope;
import com.contextflow.knowledge.dto.ProviderErrorCategory;
import com.contextflow.knowledge.port.EmbeddingClient;
import com.contextflow.knowledge.repository.KnowledgeIngestionRepository;
import com.contextflow.knowledge.worker.KnowledgeOutboxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Internal HTTP client for POST /internal/v1/ai/embeddings complying with BranOS-04-6 (connect=10s,
 * read=30s, Authorization Bearer service token, X-Provider-Credential header, strict 1536d finite
 * vector validation, and 7-category error normalization).
 */
@Component
public class HttpEmbeddingClient implements EmbeddingClient {
    private static final String EMBEDDINGS_PATH = "/internal/v1/ai/embeddings";
    private static final String PROVIDER_CREDENTIAL_HEADER = "X-Provider-Credential";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeOutboxProperties properties;
    private final Environment environment;

    public HttpEmbeddingClient(
            AiServiceProperties aiServiceProperties,
            KnowledgeOutboxProperties properties,
            ObjectMapper objectMapper,
            Environment environment) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.environment = environment;

        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                        .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()));

        this.restClient =
                RestClient.builder()
                        .baseUrl(aiServiceProperties.getBaseUrl().toString())
                        .requestFactory(requestFactory)
                        .build();
    }

    @Override
    public EmbeddingBatchResponse embedBatch(
            EmbeddingBatchRequest request, String providerCredential) {
        if (providerCredential == null || providerCredential.isBlank()) {
            throw new EmbeddingClientException(
                    ProviderErrorCategory.CONFIGURATION_BLOCKED,
                    "INVALID_API_KEY",
                    false,
                    null,
                    false,
                    "Missing platform provider credential for embedding call.");
        }
        String serviceToken = resolveInternalServiceToken();
        if (serviceToken.isBlank()) {
            throw new EmbeddingClientException(
                    ProviderErrorCategory.CONFIGURATION_BLOCKED,
                    "SERVICE_AUTH_REQUIRED",
                    false,
                    null,
                    false,
                    "Missing CF_INTERNAL_SERVICE_TOKEN for internal ai-service call.");
        }

        try {
            EmbeddingBatchResponse response =
                    restClient
                            .post()
                            .uri(EMBEDDINGS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                            .header(PROVIDER_CREDENTIAL_HEADER, providerCredential)
                            .body(request)
                            .retrieve()
                            .body(EmbeddingBatchResponse.class);

            validateResponse(request, response);
            return response;
        } catch (RestClientResponseException ex) {
            throw mapHttpFailure(ex);
        } catch (ResourceAccessException ex) {
            throw new EmbeddingClientException(
                    ProviderErrorCategory.TRANSIENT_RETRYABLE,
                    "PROVIDER_UNAVAILABLE",
                    true,
                    null,
                    false,
                    "Network or timeout error calling ai-service /embeddings",
                    ex);
        } catch (EmbeddingClientException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EmbeddingClientException(
                    ProviderErrorCategory.UNKNOWN_REQUIRES_REVIEW,
                    "ADAPTER_UNAVAILABLE",
                    false,
                    null,
                    false,
                    "Unexpected error calling ai-service /embeddings",
                    ex);
        }
    }

    private String resolveInternalServiceToken() {
        if (properties.getInternalServiceToken() != null
                && !properties.getInternalServiceToken().isBlank()) {
            return properties.getInternalServiceToken().trim();
        }
        String envVal = environment.getProperty("CF_INTERNAL_SERVICE_TOKEN");
        return envVal == null ? "" : envVal.trim();
    }

    private void validateResponse(EmbeddingBatchRequest request, EmbeddingBatchResponse response) {
        if (response == null
                || response.getVectors() == null
                || response.getVectors().size() != request.getTexts().size()) {
            throw new EmbeddingClientException(
                    ProviderErrorCategory.PERMANENT_INPUT_ERROR,
                    "INVALID_ADAPTER_OUTPUT",
                    false,
                    null,
                    true,
                    "Embedding vector count mismatch from ai-service.");
        }
        for (List<Double> vector : response.getVectors()) {
            if (vector == null
                    || vector.size() != KnowledgeIngestionRepository.EXPECTED_EMBEDDING_DIMENSION) {
                throw new EmbeddingClientException(
                        ProviderErrorCategory.PERMANENT_INPUT_ERROR,
                        "INVALID_ADAPTER_OUTPUT",
                        false,
                        null,
                        true,
                        "Embedding vector dimension mismatch from ai-service.");
            }
            for (Double val : vector) {
                if (val == null || !Double.isFinite(val)) {
                    throw new EmbeddingClientException(
                            ProviderErrorCategory.PERMANENT_INPUT_ERROR,
                            "INVALID_ADAPTER_OUTPUT",
                            false,
                            null,
                            true,
                            "Embedding vector contains non-finite element.");
                }
            }
        }
    }

    private EmbeddingClientException mapHttpFailure(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String body = ex.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            try {
                EmbeddingErrorEnvelope envelope =
                        objectMapper.readValue(body, EmbeddingErrorEnvelope.class);
                if (envelope != null
                        && envelope.getError() != null
                        && envelope.getError().getErrorCategory() != null) {
                    var detail = envelope.getError();
                    return new EmbeddingClientException(
                            detail.getErrorCategory(),
                            detail.getErrorCode() != null
                                    ? detail.getErrorCode()
                                    : "ADAPTER_UNAVAILABLE",
                            detail.isRetryable(),
                            detail.getRetryAfterSeconds(),
                            detail.isDispatched(),
                            "ai-service returned normalized error " + detail.getErrorCode(),
                            ex);
                }
            } catch (Exception ignored) {
                // Fall back to HTTP status classification below.
            }
        }

        if (status == 401 || status == 403) {
            return new EmbeddingClientException(
                    ProviderErrorCategory.CONFIGURATION_BLOCKED,
                    status == 401 ? "SERVICE_AUTH_REQUIRED" : "SERVICE_AUTH_INVALID",
                    false,
                    null,
                    false,
                    "Internal service authentication failed with HTTP " + status,
                    ex);
        }
        if (status == 422 || status == 400) {
            return new EmbeddingClientException(
                    ProviderErrorCategory.PERMANENT_INPUT_ERROR,
                    "PERMANENT_INPUT_ERROR",
                    false,
                    null,
                    false,
                    "Invalid embedding request payload (HTTP " + status + ")",
                    ex);
        }
        if (status == 429) {
            return new EmbeddingClientException(
                    ProviderErrorCategory.RATE_LIMIT_RETRYABLE,
                    "PROVIDER_RATE_LIMITED",
                    true,
                    5,
                    true,
                    "Rate limited (HTTP 429)",
                    ex);
        }
        if (status >= 500) {
            return new EmbeddingClientException(
                    ProviderErrorCategory.TRANSIENT_RETRYABLE,
                    "PROVIDER_UNAVAILABLE",
                    true,
                    null,
                    true,
                    "Upstream ai-service HTTP " + status,
                    ex);
        }
        return new EmbeddingClientException(
                ProviderErrorCategory.UNKNOWN_REQUIRES_REVIEW,
                "ADAPTER_UNAVAILABLE",
                false,
                null,
                false,
                "Unclassified ai-service HTTP " + status,
                ex);
    }
}

package com.contextflow.credential.client;

import com.contextflow.common.api.ApiProblem;
import com.contextflow.common.api.ErrorCode;
import com.contextflow.common.config.AiServiceProperties;
import com.contextflow.credential.dto.CredentialVerifyResponse;
import com.contextflow.credential.dto.InternalCredentialVerifyInput;
import com.contextflow.credential.dto.InternalCredentialVerifyResult;
import com.contextflow.credential.dto.VerifyErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
public class AiCredentialVerifyClient {
    private static final String VERIFY_PATH = "/internal/v1/ai/credentials/verify";
    private static final String NOT_TESTED = "NOT_TESTED";

    private final Environment environment;
    private final RestClient client;

    public AiCredentialVerifyClient(
            AiServiceProperties properties, Environment environment, RestClient.Builder builder) {
        this.environment = environment;
        var httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        // The local ASGI server accepts HTTP/1.1 without an h2c upgrade.
                        .version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.client =
                builder.baseUrl(properties.getBaseUrl().toString())
                        .requestFactory(requestFactory)
                        .build();
    }

    public CredentialVerifyResponse verify(String apiKey, UUID correlationId) {
        if (apiKey.isEmpty()
                || apiKey.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw ApiProblem.invalid("API key must use visible ASCII characters.");
        }

        String token = environment.getProperty("CF_INTERNAL_SERVICE_TOKEN", "");
        if (token.isBlank()) {
            log.warn("Credential verification service authentication is not configured.");
            throw unavailable();
        }

        UUID providerCallId = UUID.randomUUID();
        InternalCredentialVerifyResult result;
        try {
            result =
                    client.post()
                            .uri(VERIFY_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .headers(headers -> headers.setBearerAuth(token))
                            .header("X-Provider-Credential", apiKey)
                            .body(new InternalCredentialVerifyInput(correlationId, providerCallId))
                            .retrieve()
                            .onStatus(
                                    status -> status.value() != 200,
                                    (request, response) -> {
                                        log.warn(
                                                "Credential verification returned HTTP {}.",
                                                response.getStatusCode().value());
                                        throw unavailable();
                                    })
                            .body(InternalCredentialVerifyResult.class);
        } catch (RestClientException | IllegalArgumentException failure) {
            log.warn(
                    "Credential verification exchange failed ({}).",
                    failure.getClass().getSimpleName());
            throw unavailable();
        }

        if (result == null
                || !correlationId.equals(result.getCorrelationId())
                || !providerCallId.equals(result.getProviderCallId())
                || result.getValid() == null
                || result.getCheckedAt() == null
                || !NOT_TESTED.equals(result.getCapabilityStatus())
                || result.getValid() != (result.getErrorCode() == null)) {
            log.warn("Credential verification result did not satisfy the internal contract.");
            throw unavailable();
        }
        if (result.getErrorCode() == VerifyErrorCode.PROVIDER_UNAVAILABLE) {
            throw unavailable();
        }
        return new CredentialVerifyResponse(
                result.getValid(), result.getCheckedAt(), result.getErrorCode(), NOT_TESTED);
    }

    private static ApiProblem unavailable() {
        return new ApiProblem(
                ErrorCode.PROVIDER_UNAVAILABLE, "Credential verification is unavailable.");
    }
}

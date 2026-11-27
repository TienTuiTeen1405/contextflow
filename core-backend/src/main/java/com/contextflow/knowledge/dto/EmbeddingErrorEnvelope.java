package com.contextflow.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingErrorEnvelope {
    private ErrorDetail error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorDetail {
        private boolean dispatched;

        @JsonProperty("error_category")
        private ProviderErrorCategory errorCategory;

        @JsonProperty("error_code")
        private String errorCode;

        private boolean retryable;

        @JsonProperty("retry_after_seconds")
        private Integer retryAfterSeconds;
    }
}

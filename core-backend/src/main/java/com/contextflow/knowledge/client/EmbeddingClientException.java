package com.contextflow.knowledge.client;

import com.contextflow.knowledge.dto.ProviderErrorCategory;
import lombok.Getter;

@Getter
public class EmbeddingClientException extends RuntimeException {
    private final ProviderErrorCategory errorCategory;
    private final String errorCode;
    private final boolean retryable;
    private final Integer retryAfterSeconds;
    private final boolean dispatched;

    public EmbeddingClientException(
            ProviderErrorCategory errorCategory,
            String errorCode,
            boolean retryable,
            Integer retryAfterSeconds,
            boolean dispatched,
            String message,
            Throwable cause) {
        super(message, cause);
        this.errorCategory = errorCategory;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
        this.dispatched = dispatched;
    }

    public EmbeddingClientException(
            ProviderErrorCategory errorCategory,
            String errorCode,
            boolean retryable,
            Integer retryAfterSeconds,
            boolean dispatched,
            String message) {
        this(errorCategory, errorCode, retryable, retryAfterSeconds, dispatched, message, null);
    }
}

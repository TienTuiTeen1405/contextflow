package com.contextflow.common.api;

public record ApiError(String code, String message, boolean retryable, String correlationId) {
    public record Envelope(ApiError error) {}
}

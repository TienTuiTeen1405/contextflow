package com.contextflow.common.api;

public enum ErrorCode {
    VALIDATION_ERROR(400, false),
    INVALID_SSE_CURSOR(400, false),
    UNAUTHENTICATED(401, false),
    ACCOUNT_DISABLED(403, false),
    FORBIDDEN(403, false),
    RESOURCE_NOT_FOUND(404, false),
    PROJECT_DELETING(409, false),
    CHAT_SCOPE_LOCKED(409, false),
    IDEMPOTENCY_KEY_REUSED(409, false),
    CHAT_REQUEST_IN_PROGRESS(409, true),
    CONFLICT_STALE_REVISION(409, false),
    SOURCE_DELETED(410, false),
    SSE_CURSOR_EXPIRED(410, false),
    USER_CREDENTIAL_UNAVAILABLE(422, false),
    DISCLOSURE_REQUIRED(422, false),
    INVALID_EXPLICIT_REFERENCE(422, false),
    EXPLICIT_REFERENCE_BUDGET_EXCEEDED(422, false),
    STAGE_CONTEXT_LIMIT(422, false),
    BRANCH_CONTEXT_LIMIT(422, false),
    APP_USAGE_LIMIT_EXCEEDED(429, false),
    PROVIDER_RATE_LIMITED(429, true),
    PROVIDER_QUOTA_EXHAUSTED(429, false),
    PLATFORM_CREDENTIAL_UNAVAILABLE(503, false),
    PROVIDER_UNAVAILABLE(503, true),
    WORKER_LEASE_EXPIRED(null, false);

    private final Integer httpStatus;
    private final boolean retryable;

    ErrorCode(Integer httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}

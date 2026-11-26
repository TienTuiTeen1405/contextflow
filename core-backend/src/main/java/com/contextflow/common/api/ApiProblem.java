package com.contextflow.common.api;

import com.contextflow.contracts.generated.PublicApi.ErrorCode;

public final class ApiProblem extends RuntimeException {
    private final ErrorCode code;

    public ApiProblem(ErrorCode code, String message) {
        super(message);
        if (code.httpStatus() == null)
            throw new IllegalArgumentException("REST error code required");
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiProblem invalid(String message) {
        return new ApiProblem(ErrorCode.VALIDATION_ERROR, message);
    }

    public static ApiProblem missing() {
        return new ApiProblem(ErrorCode.RESOURCE_NOT_FOUND, "Resource not found.");
    }
}

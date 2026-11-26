package com.contextflow.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiError {
    private ErrorCode code;
    private String message;
    private boolean retryable;
    private UUID correlationId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode details;
}

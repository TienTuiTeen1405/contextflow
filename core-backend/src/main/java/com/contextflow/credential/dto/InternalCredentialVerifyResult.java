package com.contextflow.credential.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class InternalCredentialVerifyResult {
    private UUID correlationId;
    private UUID providerCallId;
    private Boolean valid;
    private Instant checkedAt;
    private VerifyErrorCode errorCode;
    private String capabilityStatus;
}

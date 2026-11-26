package com.contextflow.credential.dto;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CredentialVerifyResponse {
    private final boolean valid;
    private final Instant checkedAt;
    private final VerifyErrorCode errorCode;
    private final String capabilityStatus;
}

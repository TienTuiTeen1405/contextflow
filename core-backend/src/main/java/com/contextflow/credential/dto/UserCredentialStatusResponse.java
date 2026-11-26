package com.contextflow.credential.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserCredentialStatusResponse {
    private final boolean configured;
    private final UUID credentialId;
    private final Long credentialVersion;
    private final CredentialStatus status;
    private final String maskedLabel;
    private final DeclaredTier declaredTier;
    private final String googleCloudProjectRef;
    private final Instant verifiedAt;
    private final boolean disclosureRequired;
}

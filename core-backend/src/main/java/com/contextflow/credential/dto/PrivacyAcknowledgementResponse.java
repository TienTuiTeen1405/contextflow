package com.contextflow.credential.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PrivacyAcknowledgementResponse {
    private final UUID id;
    private final UUID credentialId;
    private final long credentialVersion;
    private final String disclosureVersion;
    private final DisclosureScope scope;
    private final DeclaredTier effectiveTier;
    private final String googleCloudProjectRef;
    private final Instant acknowledgedAt;
}

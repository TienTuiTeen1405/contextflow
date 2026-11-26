package com.contextflow.credential.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PrivacyAcknowledgementRequest {
    @NotNull private UUID credentialId;
    @NotNull @Positive private Long credentialVersion;
    @NotBlank private String disclosureVersion;
    @NotNull private DisclosureScope scope;
}

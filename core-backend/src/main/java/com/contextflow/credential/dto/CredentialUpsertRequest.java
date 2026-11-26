package com.contextflow.credential.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CredentialUpsertRequest {
    @NotBlank
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String apiKey;

    @NotNull private DeclaredTier declaredTier;
    private String googleCloudProjectRef;
    private String disclosureVersionAcknowledged;
}

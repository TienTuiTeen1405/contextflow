package com.contextflow.credential.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CredentialVerifyRequest {
    @NotBlank
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String apiKey;
}

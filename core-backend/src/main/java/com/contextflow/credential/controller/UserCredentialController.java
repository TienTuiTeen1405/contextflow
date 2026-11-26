package com.contextflow.credential.controller;

import com.contextflow.common.api.CorrelationIdFilter;
import com.contextflow.common.api.ErrorEnvelope;
import com.contextflow.credential.dto.CredentialUpsertRequest;
import com.contextflow.credential.dto.CredentialVerifyRequest;
import com.contextflow.credential.dto.CredentialVerifyResponse;
import com.contextflow.credential.dto.PrivacyAcknowledgementRequest;
import com.contextflow.credential.dto.PrivacyAcknowledgementResponse;
import com.contextflow.credential.dto.UserCredentialStatusResponse;
import com.contextflow.credential.service.PrivacyAcknowledgementResult;
import com.contextflow.credential.service.UserCredentialService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@ApiResponse(
        responseCode = "401",
        description = "UNAUTHENTICATED",
        content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
@ApiResponse(
        responseCode = "403",
        description = "ACCOUNT_DISABLED",
        content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
public class UserCredentialController {
    private final UserCredentialService service;

    @GetMapping("/credential")
    @ApiResponse(
            responseCode = "200",
            description = "Credential status",
            useReturnTypeSchema = true)
    public UserCredentialStatusResponse status() {
        return service.getStatus();
    }

    @PostMapping("/credential/verify")
    @ApiResponse(
            responseCode = "200",
            description = "Verification result",
            useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    @ApiResponse(
            responseCode = "503",
            description = "PROVIDER_UNAVAILABLE",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    public CredentialVerifyResponse verify(
            @Valid @RequestBody CredentialVerifyRequest request,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId) {
        return service.verify(request, UUID.fromString(correlationId));
    }

    @PutMapping("/credential")
    @ApiResponse(responseCode = "200", description = "Credential saved", useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    @ApiResponse(
            responseCode = "422",
            description = "DISCLOSURE_REQUIRED or USER_CREDENTIAL_UNAVAILABLE",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    @ApiResponse(
            responseCode = "503",
            description = "PROVIDER_UNAVAILABLE",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    public UserCredentialStatusResponse upsert(
            @Valid @RequestBody CredentialUpsertRequest request,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId) {
        return service.upsert(request, UUID.fromString(correlationId));
    }

    @DeleteMapping("/credential")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Credential removed or already absent")
    public void remove() {
        service.remove();
    }

    @PostMapping("/privacy-acknowledgements")
    @ApiResponse(
            responseCode = "200",
            description = "Existing acknowledgement",
            useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "201",
            description = "Acknowledgement created",
            useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    @ApiResponse(
            responseCode = "404",
            description = "RESOURCE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    @ApiResponse(
            responseCode = "422",
            description = "DISCLOSURE_REQUIRED or USER_CREDENTIAL_UNAVAILABLE",
            content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    public ResponseEntity<PrivacyAcknowledgementResponse> acknowledge(
            @Valid @RequestBody PrivacyAcknowledgementRequest request) {
        PrivacyAcknowledgementResult result = service.acknowledge(request);
        return ResponseEntity.status(result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.getReceipt());
    }
}

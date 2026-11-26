package com.contextflow.credential.service;

import com.contextflow.credential.dto.CredentialUpsertRequest;
import com.contextflow.credential.dto.CredentialVerifyRequest;
import com.contextflow.credential.dto.CredentialVerifyResponse;
import com.contextflow.credential.dto.PrivacyAcknowledgementRequest;
import com.contextflow.credential.dto.UserCredentialStatusResponse;
import java.util.UUID;

public interface UserCredentialService {
    UserCredentialStatusResponse getStatus();

    CredentialVerifyResponse verify(CredentialVerifyRequest request, UUID correlationId);

    UserCredentialStatusResponse upsert(CredentialUpsertRequest request, UUID correlationId);

    void remove();

    PrivacyAcknowledgementResult acknowledge(PrivacyAcknowledgementRequest request);
}

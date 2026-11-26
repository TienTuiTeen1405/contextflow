package com.contextflow.credential.service.impl;

import com.contextflow.ai.service.ChatCredentialRemovalService;
import com.contextflow.common.api.ApiProblem;
import com.contextflow.common.api.ErrorCode;
import com.contextflow.credential.client.AiCredentialVerifyClient;
import com.contextflow.credential.crypto.EnvelopeCryptoService;
import com.contextflow.credential.crypto.EnvelopeCryptoService.EncryptedSecret;
import com.contextflow.credential.dto.CredentialStatus;
import com.contextflow.credential.dto.CredentialUpsertRequest;
import com.contextflow.credential.dto.CredentialVerifyRequest;
import com.contextflow.credential.dto.CredentialVerifyResponse;
import com.contextflow.credential.dto.DisclosureScope;
import com.contextflow.credential.dto.PrivacyAcknowledgementRequest;
import com.contextflow.credential.dto.UserCredentialStatusResponse;
import com.contextflow.credential.entity.AiCredential;
import com.contextflow.credential.entity.PrivacyDisclosureAcknowledgement;
import com.contextflow.credential.mapper.CredentialMapper;
import com.contextflow.credential.repository.AiCredentialRepository;
import com.contextflow.credential.repository.CredentialOwnerLock;
import com.contextflow.credential.repository.PrivacyDisclosureAcknowledgementRepository;
import com.contextflow.credential.service.PrivacyAcknowledgementResult;
import com.contextflow.credential.service.UserCredentialService;
import com.contextflow.identity.service.CurrentUserProvider;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class UserCredentialServiceImpl implements UserCredentialService {
    private static final String DISCLOSURE_VERSION = "v1";
    private static final String USER_SCOPE = "USER";

    private final CurrentUserProvider currentUserProvider;
    private final CredentialOwnerLock credentialOwnerLock;
    private final AiCredentialRepository credentials;
    private final PrivacyDisclosureAcknowledgementRepository acknowledgements;
    private final CredentialMapper mapper;
    private final AiCredentialVerifyClient verifyClient;
    private final EnvelopeCryptoService crypto;
    private final TransactionTemplate transactionTemplate;
    private final ChatCredentialRemovalService chatCredentialRemovalService;

    @Override
    @Transactional(readOnly = true)
    public UserCredentialStatusResponse getStatus() {
        UUID userId = currentUserProvider.getCurrentUserId();
        return activeCredential(userId)
                .map(credential ->
                        mapper.toStatus(
                                credential,
                                credential.getDeclaredTier().requiresDisclosure()
                                        && receipt(credential, DisclosureScope.WORKSPACE_OPERATION)
                                                .isEmpty()))
                .orElseGet(() -> UserCredentialStatusResponse.builder().build());
    }

    @Override
    public CredentialVerifyResponse verify(CredentialVerifyRequest request, UUID correlationId) {
        currentUserProvider.getCurrentUserId();
        return verifyClient.verify(request.getApiKey(), correlationId);
    }

    @Override
    public UserCredentialStatusResponse upsert(
            CredentialUpsertRequest request, UUID correlationId) {
        UUID userId = currentUserProvider.getCurrentUserId();
        if (request.getDeclaredTier().requiresDisclosure()) {
            requireCurrentDisclosure(request.getDisclosureVersionAcknowledged());
        }
        String apiKey = request.getApiKey();
        String projectRef = request.getGoogleCloudProjectRef();
        if (projectRef != null && projectRef.contains(apiKey)) {
            throw ApiProblem.invalid(
                    "Google Cloud project reference must not contain the API key.");
        }
        CredentialVerifyResponse verified = verifyClient.verify(apiKey, correlationId);
        if (!verified.isValid()) {
            throw new ApiProblem(
                    ErrorCode.USER_CREDENTIAL_UNAVAILABLE, "The API key could not be verified.");
        }

        UUID credentialId = UUID.randomUUID();
        EncryptedSecret encryptedSecret;
        try {
            encryptedSecret = crypto.encrypt(userId, credentialId, apiKey);
        } catch (GeneralSecurityException ignored) {
            throw new ApiProblem(
                    ErrorCode.PROVIDER_UNAVAILABLE, "Credential encryption is unavailable.");
        }
        AiCredential next =
                AiCredential.builder()
                        .id(credentialId)
                        .ownerScope(USER_SCOPE)
                        .ownerUserId(userId)
                        .status(CredentialStatus.ACTIVE)
                        .maskedLabel(mask(apiKey))
                        .encryptedSecret(encryptedSecret.getEncryptedSecret())
                        .wrappedDek(encryptedSecret.getWrappedDek())
                        .kekVersion(encryptedSecret.getKekVersion())
                        .declaredTier(request.getDeclaredTier())
                        .googleCloudProjectRef(projectRef)
                        .verifiedAt(verified.getCheckedAt().truncatedTo(ChronoUnit.MICROS))
                        .build();
        return transactionTemplate.execute(status -> rotateAndSave(next));
    }

    @Override
    @Transactional
    public void remove() {
        UUID userId = currentUserProvider.getCurrentUserId();
        lockActiveAccount(userId);
        activeCredential(userId)
                .ifPresent(credential -> {
                    credential.setStatus(CredentialStatus.REMOVED);
                    credential.setEncryptedSecret(new byte[0]);
                    credential.setWrappedDek(new byte[0]);
                    credential.setUpdatedAt(Instant.now());
                    credentials.flush();
                    chatCredentialRemovalService.cancelAfterRemoval(
                            userId, credential.getId(), credential.getCredentialVersion());
                });
    }

    @Override
    @Transactional
    public PrivacyAcknowledgementResult acknowledge(PrivacyAcknowledgementRequest request) {
        UUID userId = currentUserProvider.getCurrentUserId();
        lockActiveAccount(userId);
        AiCredential credential =
                activeCredential(userId)
                        .orElseThrow(() ->
                                new ApiProblem(
                                        ErrorCode.USER_CREDENTIAL_UNAVAILABLE,
                                        "No active user credential."));
        if (!credential.getId().equals(request.getCredentialId())
                || credential.getCredentialVersion() != request.getCredentialVersion()) {
            throw ApiProblem.missing();
        }
        if (!credential.getDeclaredTier().requiresDisclosure()) {
            throw ApiProblem.invalid(
                    "Unpaid Services acknowledgement requires a FREE or UNKNOWN tier.");
        }
        requireCurrentDisclosure(request.getDisclosureVersion());

        return receipt(credential, request.getScope())
                .map(existing ->
                        new PrivacyAcknowledgementResult(mapper.toReceipt(existing), false))
                .orElseGet(() ->
                        new PrivacyAcknowledgementResult(
                                mapper.toReceipt(saveReceipt(credential, request.getScope())),
                                true));
    }

    private UserCredentialStatusResponse rotateAndSave(AiCredential next) {
        UUID userId = next.getOwnerUserId();
        lockActiveAccount(userId);
        Instant now = Instant.now();
        next.setCredentialVersion(Math.addExact(credentials.findLatestUserVersion(userId), 1));
        next.setCreatedAt(now);
        next.setUpdatedAt(now);
        activeCredential(userId)
                .ifPresent(previous -> {
                    previous.setStatus(CredentialStatus.ROTATED);
                    previous.setUpdatedAt(now);
                    // The partial unique index must see the previous row leave ACTIVE first.
                    credentials.flush();
                });
        AiCredential saved = credentials.saveAndFlush(next);
        boolean requiresDisclosure = saved.getDeclaredTier().requiresDisclosure();
        if (requiresDisclosure) {
            saveReceipt(saved, DisclosureScope.CREDENTIAL_SETUP);
        }
        return mapper.toStatus(saved, requiresDisclosure);
    }

    private Optional<AiCredential> activeCredential(UUID userId) {
        return credentials.findUserCredential(userId, CredentialStatus.ACTIVE);
    }

    private Optional<PrivacyDisclosureAcknowledgement> receipt(
            AiCredential credential, DisclosureScope scope) {
        return acknowledgements.findReceipt(
                credential.getOwnerUserId(),
                credential.getId(),
                credential.getCredentialVersion(),
                DISCLOSURE_VERSION,
                scope);
    }

    private PrivacyDisclosureAcknowledgement saveReceipt(
            AiCredential credential, DisclosureScope scope) {
        return acknowledgements.save(
                PrivacyDisclosureAcknowledgement.builder()
                        .id(UUID.randomUUID())
                        .actorUserId(credential.getOwnerUserId())
                        .credentialId(credential.getId())
                        .credentialVersion(credential.getCredentialVersion())
                        .disclosureVersion(DISCLOSURE_VERSION)
                        .scope(scope)
                        .effectiveTier(credential.getDeclaredTier())
                        .googleCloudProjectRef(credential.getGoogleCloudProjectRef())
                        .acknowledgedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                        .build());
    }

    private void lockActiveAccount(UUID userId) {
        String status =
                credentialOwnerLock
                        .lockAccountStatus(userId)
                        .orElseThrow(() ->
                                new ApiProblem(ErrorCode.UNAUTHENTICATED, "Account unavailable."));
        if (!"ACTIVE".equals(status)) {
            throw new ApiProblem(ErrorCode.ACCOUNT_DISABLED, "Account is disabled.");
        }
    }

    private static void requireCurrentDisclosure(String version) {
        if (!DISCLOSURE_VERSION.equals(version)) {
            throw new ApiProblem(
                    ErrorCode.DISCLOSURE_REQUIRED,
                    "The current Unpaid Services disclosure must be acknowledged.");
        }
    }

    private static String mask(String apiKey) {
        return apiKey.length() < 8 ? "****" : "****" + apiKey.substring(apiKey.length() - 4);
    }
}

package com.contextflow.credential.repository;

import com.contextflow.credential.dto.DisclosureScope;
import com.contextflow.credential.entity.PrivacyDisclosureAcknowledgement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrivacyDisclosureAcknowledgementRepository
        extends JpaRepository<PrivacyDisclosureAcknowledgement, UUID> {
    @Query(
            """
            SELECT a FROM PrivacyDisclosureAcknowledgement a
            WHERE a.actorUserId = :userId
                AND a.credentialId = :credentialId AND a.credentialVersion = :credentialVersion
                AND a.disclosureVersion = :disclosureVersion AND a.scope = :scope
            """)
    Optional<PrivacyDisclosureAcknowledgement> findReceipt(
            @Param("userId") UUID userId,
            @Param("credentialId") UUID credentialId,
            @Param("credentialVersion") long credentialVersion,
            @Param("disclosureVersion") String disclosureVersion,
            @Param("scope") DisclosureScope scope);
}

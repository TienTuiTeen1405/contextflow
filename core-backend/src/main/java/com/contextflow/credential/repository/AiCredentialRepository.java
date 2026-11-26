package com.contextflow.credential.repository;

import com.contextflow.credential.dto.CredentialStatus;
import com.contextflow.credential.entity.AiCredential;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiCredentialRepository extends JpaRepository<AiCredential, UUID> {
    @Query(
            """
            SELECT c FROM AiCredential c
            WHERE c.ownerScope = 'USER' AND c.ownerUserId = :userId AND c.status = :status
            """)
    Optional<AiCredential> findUserCredential(
            @Param("userId") UUID userId, @Param("status") CredentialStatus status);

    @Query(
            """
            SELECT COALESCE(MAX(c.credentialVersion), 0) FROM AiCredential c
            WHERE c.ownerScope = 'USER' AND c.ownerUserId = :userId
            """)
    long findLatestUserVersion(@Param("userId") UUID userId);
}

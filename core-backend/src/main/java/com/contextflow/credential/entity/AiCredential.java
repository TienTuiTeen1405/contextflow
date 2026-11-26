package com.contextflow.credential.entity;

import com.contextflow.credential.dto.CredentialStatus;
import com.contextflow.credential.dto.DeclaredTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ai_credentials", schema = "contextflow")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AiCredential {
    @Id private UUID id;

    @Column(nullable = false)
    private String ownerScope;

    private UUID ownerUserId;

    @Column(nullable = false)
    private long credentialVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CredentialStatus status;

    @Column(nullable = false)
    private String maskedLabel;

    @Column(nullable = false)
    private byte[] encryptedSecret;

    @Column(nullable = false)
    private byte[] wrappedDek;

    @Column(nullable = false)
    private String kekVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeclaredTier declaredTier;

    private String googleCloudProjectRef;
    private Instant verifiedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}

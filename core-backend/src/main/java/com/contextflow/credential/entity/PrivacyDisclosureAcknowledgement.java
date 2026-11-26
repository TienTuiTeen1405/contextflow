package com.contextflow.credential.entity;

import com.contextflow.credential.dto.DeclaredTier;
import com.contextflow.credential.dto.DisclosureScope;
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

@Entity
@Table(name = "privacy_disclosure_acknowledgements", schema = "contextflow")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PrivacyDisclosureAcknowledgement {
    @Id private UUID id;

    @Column(nullable = false)
    private UUID actorUserId;

    @Column(nullable = false)
    private UUID credentialId;

    @Column(nullable = false)
    private long credentialVersion;

    @Column(nullable = false)
    private String disclosureVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisclosureScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeclaredTier effectiveTier;

    private String googleCloudProjectRef;

    @Column(nullable = false)
    private Instant acknowledgedAt;
}

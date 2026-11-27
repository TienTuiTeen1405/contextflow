package com.contextflow.context.repository;

import com.contextflow.context.dto.PrivacyTier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CredentialTierSnapshotRecord {
    private PrivacyTier userTier;
    private PrivacyTier platformTier;
}

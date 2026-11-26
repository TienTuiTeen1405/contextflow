package com.contextflow.ai.service;

import java.util.UUID;

public interface ChatCredentialRemovalService {
    /** Invalidates dependent continuity and cancels open requests inside credential removal. */
    void cancelAfterRemoval(UUID userId, UUID credentialId, long credentialVersion);
}

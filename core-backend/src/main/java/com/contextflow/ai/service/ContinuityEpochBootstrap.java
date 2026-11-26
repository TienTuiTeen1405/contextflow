package com.contextflow.ai.service;

import java.util.UUID;

/** Initializes the initial active continuity epoch for Main Stage 1 of a new Conversation. */
public interface ContinuityEpochBootstrap {
    UUID initializeForMainStage(UUID projectId, UUID conversationId, UUID mainStageId);
}

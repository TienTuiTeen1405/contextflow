package com.contextflow.ai.service.impl;

import com.contextflow.ai.service.ContinuityEpochBootstrap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContinuityEpochBootstrapImpl implements ContinuityEpochBootstrap {
    private static final String MOCK_COMPATIBILITY_SEED = "DEMO|MOCK|INTERACTIONS|mock|v1";

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public UUID initializeForMainStage(UUID projectId, UUID conversationId, UUID mainStageId) {
        UUID epochId = UUID.randomUUID();
        byte[] compatibilityKey = sha256(MOCK_COMPATIBILITY_SEED);
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.continuity_epochs (
                    id, project_id, conversation_id, main_stage_id,
                    route_kind, route_id, epoch_no, status,
                    provider, api_interface, model, compatibility_key
                )
                VALUES (?, ?, ?, ?, 'MAIN', ?, 1, 'ACTIVE', 'MOCK', 'INTERACTIONS', 'mock', ?)
                """,
                epochId,
                projectId,
                conversationId,
                mainStageId,
                mainStageId,
                compatibilityKey);
        return epochId;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}

package com.contextflow.ai.repository;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChatCredentialRemovalRepository {
    private final JdbcTemplate jdbcTemplate;

    public void invalidateDependentEpochs(UUID userId, UUID credentialId, long credentialVersion) {
        jdbcTemplate.update(
                """
                WITH locked_epochs AS (
                    SELECT epoch.id
                    FROM contextflow.continuity_epochs epoch
                    JOIN contextflow.projects project ON project.id = epoch.project_id
                    WHERE epoch.status = 'ACTIVE'
                        AND project.owner_user_id = ?
                        AND EXISTS (
                            SELECT 1
                            FROM contextflow.ai_requests request
                            WHERE request.user_id = ? AND request.epoch_id = epoch.id
                                AND (
                                    request.status IN ('ACCEPTED', 'IN_PROGRESS')
                                    OR EXISTS (
                                        SELECT 1 FROM contextflow.ai_usage_records usage
                                        WHERE usage.correlation_id = request.correlation_id
                                            AND usage.contextflow_user_id = request.user_id
                                            AND usage.owner_scope = 'USER'
                                            AND usage.credential_id = ?
                                            AND usage.credential_version = ?
                                    )
                                )
                        )
                    ORDER BY epoch.id
                    FOR UPDATE OF epoch
                )
                UPDATE contextflow.continuity_epochs epoch
                SET status = 'INVALIDATED', invalidation_reason = 'CREDENTIAL_REMOVED'
                FROM locked_epochs
                WHERE epoch.id = locked_epochs.id
                """,
                userId,
                userId,
                credentialId,
                credentialVersion);
    }

    public void cancelOpenRequests(UUID userId) {
        jdbcTemplate.update(
                """
                WITH locked_requests AS (
                    SELECT id FROM contextflow.ai_requests
                    WHERE user_id = ? AND status IN ('ACCEPTED', 'IN_PROGRESS')
                    ORDER BY id
                    FOR UPDATE
                ), cancelled AS (
                    UPDATE contextflow.ai_requests request
                    SET status = 'CANCELLED', finished_at = now(), queued_input = NULL,
                        worker_token = NULL, lease_expires_at = NULL, error_code = NULL,
                        last_event_id = request.last_event_id + 1
                    FROM locked_requests
                    WHERE request.id = locked_requests.id
                        AND request.status IN ('ACCEPTED', 'IN_PROGRESS')
                    RETURNING request.id, request.last_event_id
                )
                INSERT INTO contextflow.ai_request_events (request_id, event_id, event_type, payload)
                SELECT id, last_event_id, 'request.cancelled',
                    jsonb_build_object('requestId', id, 'reason', 'CREDENTIAL_REMOVED')
                FROM cancelled
                """,
                userId);
    }
}

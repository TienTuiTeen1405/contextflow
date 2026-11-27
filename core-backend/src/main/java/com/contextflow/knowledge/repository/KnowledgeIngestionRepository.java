package com.contextflow.knowledge.repository;

import com.contextflow.knowledge.dto.ActiveIndexGenerationDto;
import com.contextflow.knowledge.dto.ClaimedOutboxTask;
import com.contextflow.knowledge.dto.KnowledgeChunkDraft;
import com.contextflow.knowledge.dto.KnowledgeItemType;
import com.contextflow.knowledge.dto.OutboxStatus;
import com.contextflow.knowledge.dto.ProviderErrorCategory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL 16 repository for Project KB items, chunks (pgvector 1536d + cf_vi FTS +
 * technical_terms), and embedding_outbox leasing / 5-guard vector writes.
 */
@Repository
@RequiredArgsConstructor
public class KnowledgeIngestionRepository {
    public static final int EXPECTED_EMBEDDING_DIMENSION = 1536;

    private final JdbcTemplate jdbcTemplate;

    public Optional<ActiveIndexGenerationDto> findActiveIndexGeneration(UUID projectId) {
        List<ActiveIndexGenerationDto> rows =
                jdbcTemplate.query(
                        """
                        SELECT id, project_id, embedding_model, output_dimensionality
                        FROM contextflow.index_generations
                        WHERE project_id = ? AND status = 'ACTIVE'
                        LIMIT 1
                        """,
                        (rs, rowNum) ->
                                ActiveIndexGenerationDto.builder()
                                        .id(rs.getObject("id", UUID.class))
                                        .projectId(rs.getObject("project_id", UUID.class))
                                        .embeddingModel(rs.getString("embedding_model"))
                                        .outputDimensionality(rs.getInt("output_dimensionality"))
                                        .build(),
                        projectId);
        return rows.stream().findFirst();
    }

    public Optional<UUID> findMainTurnKnowledgeItemId(UUID projectId, UUID turnId) {
        List<UUID> ids =
                jdbcTemplate.query(
                        """
                        SELECT id
                        FROM contextflow.knowledge_items
                        WHERE project_id = ?
                          AND item_type = 'MAIN_TURN'
                          AND source_turn_id = ?
                        LIMIT 1
                        """,
                        (rs, rowNum) -> rs.getObject("id", UUID.class),
                        projectId,
                        turnId);
        return ids.stream().findFirst();
    }

    public UUID insertKnowledgeItem(
            UUID projectId,
            KnowledgeItemType itemType,
            UUID conversationId,
            UUID stageId,
            UUID turnId,
            UUID branchId,
            long sourceRevision,
            String title,
            String content) {
        UUID itemId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.knowledge_items (
                    id, project_id, item_type, source_conversation_id, source_stage_id,
                    source_turn_id, source_branch_id, source_revision, title, content, is_active
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)
                """,
                itemId,
                projectId,
                itemType.name(),
                conversationId,
                stageId,
                turnId,
                branchId,
                sourceRevision,
                title,
                content);
        return itemId;
    }

    public UUID insertKnowledgeChunk(
            UUID projectId,
            UUID knowledgeItemId,
            UUID indexGenerationId,
            UUID sourceStageId,
            String sourceType,
            long sourceRevision,
            KnowledgeChunkDraft draft) {
        UUID chunkId = UUID.randomUUID();
        String[] termsArray =
                draft.getTechnicalTerms() == null
                        ? new String[0]
                        : draft.getTechnicalTerms().toArray(String[]::new);
        jdbcTemplate.update(connection -> {
            PreparedStatement ps =
                    connection.prepareStatement(
                            """
                            INSERT INTO contextflow.knowledge_chunks (
                                id, project_id, knowledge_item_id, index_generation_id,
                                source_stage_id, source_type, source_revision, chunk_index,
                                content, search_text, technical_terms, is_active
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)
                            """);
            ps.setObject(1, chunkId);
            ps.setObject(2, projectId);
            ps.setObject(3, knowledgeItemId);
            ps.setObject(4, indexGenerationId);
            ps.setObject(5, sourceStageId);
            ps.setString(6, sourceType);
            ps.setLong(7, sourceRevision);
            ps.setInt(8, draft.getChunkIndex());
            ps.setString(9, draft.getContent());
            ps.setString(10, draft.getSearchText());
            Array sqlArray = connection.createArrayOf("text", termsArray);
            ps.setArray(11, sqlArray);
            return ps;
        });
        return chunkId;
    }

    public UUID insertOutboxItem(
            UUID projectId,
            UUID knowledgeItemId,
            UUID chunkId,
            UUID indexGenerationId,
            long sourceRevision,
            String contentHash,
            int estimatedTokens) {
        UUID outboxId = UUID.randomUUID();
        String idempotencyKey =
                chunkId + ":" + indexGenerationId + ":" + sourceRevision + ":" + contentHash;
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.embedding_outbox (
                    id, project_id, knowledge_item_id, chunk_id, index_generation_id,
                    source_revision, idempotency_key, status, estimated_tokens, attempt_count,
                    next_retry_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, 0, now())
                """,
                outboxId,
                projectId,
                knowledgeItemId,
                chunkId,
                indexGenerationId,
                sourceRevision,
                idempotencyKey,
                Math.max(0, estimatedTokens));
        return outboxId;
    }

    @Transactional
    public int reclaimExpiredLeases(Instant now) {
        return jdbcTemplate.update(
                """
                UPDATE contextflow.embedding_outbox
                SET status = 'PENDING',
                    worker_token = NULL,
                    lease_expires_at = NULL,
                    updated_at = now()
                WHERE status = 'IN_PROGRESS'
                  AND lease_expires_at < ?
                """,
                Timestamp.from(now));
    }

    /**
     * Claims up to {@code batchSize} due PENDING outbox items belonging to the same (project_id,
     * index_generation_id) using FOR UPDATE SKIP LOCKED so each batch shares one credential and
     * index generation scope.
     */
    @Transactional
    public List<ClaimedOutboxTask> claimPendingBatch(
            UUID workerToken, Instant now, Instant leaseExpiresAt, int batchSize) {
        Timestamp nowTs = Timestamp.from(now);
        List<ScopeRow> scopes =
                jdbcTemplate.query(
                        """
                        SELECT project_id, index_generation_id
                        FROM contextflow.embedding_outbox
                        WHERE status = 'PENDING' AND next_retry_at <= ?
                        ORDER BY next_retry_at ASC, created_at ASC
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """,
                        (rs, rowNum) ->
                                new ScopeRow(
                                        rs.getObject("project_id", UUID.class),
                                        rs.getObject("index_generation_id", UUID.class)),
                        nowTs);
        if (scopes.isEmpty()) {
            return List.of();
        }
        ScopeRow scope = scopes.getFirst();

        List<UUID> candidateIds =
                jdbcTemplate.query(
                        """
                        SELECT id
                        FROM contextflow.embedding_outbox
                        WHERE status = 'PENDING'
                          AND next_retry_at <= ?
                          AND project_id = ?
                          AND index_generation_id = ?
                        ORDER BY next_retry_at ASC, created_at ASC
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                        (rs, rowNum) -> rs.getObject("id", UUID.class),
                        nowTs,
                        scope.projectId,
                        scope.indexGenerationId,
                        batchSize);
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        Timestamp leaseTs = Timestamp.from(leaseExpiresAt);
        for (UUID outboxId : candidateIds) {
            jdbcTemplate.update(
                    """
                    UPDATE contextflow.embedding_outbox
                    SET status = 'IN_PROGRESS',
                        worker_token = ?,
                        lease_expires_at = ?,
                        attempt_count = attempt_count + 1,
                        updated_at = now()
                    WHERE id = ? AND status = 'PENDING'
                    """,
                    workerToken,
                    leaseTs,
                    outboxId);
        }

        return jdbcTemplate.query(
                """
                SELECT o.id AS outbox_id,
                       o.project_id,
                       o.knowledge_item_id,
                       o.chunk_id,
                       o.index_generation_id,
                       o.source_revision,
                       o.estimated_tokens,
                       o.attempt_count,
                       o.worker_token,
                       c.content,
                       g.embedding_model,
                       g.output_dimensionality
                FROM contextflow.embedding_outbox o
                JOIN contextflow.knowledge_chunks c
                  ON c.project_id = o.project_id
                 AND c.knowledge_item_id = o.knowledge_item_id
                 AND c.index_generation_id = o.index_generation_id
                 AND c.id = o.chunk_id
                JOIN contextflow.index_generations g
                  ON g.project_id = o.project_id
                 AND g.id = o.index_generation_id
                WHERE o.worker_token = ?
                  AND o.status = 'IN_PROGRESS'
                ORDER BY o.created_at ASC, o.id ASC
                """,
                (rs, rowNum) -> {
                    String content = rs.getString("content");
                    return ClaimedOutboxTask.builder()
                            .outboxId(rs.getObject("outbox_id", UUID.class))
                            .projectId(rs.getObject("project_id", UUID.class))
                            .knowledgeItemId(rs.getObject("knowledge_item_id", UUID.class))
                            .chunkId(rs.getObject("chunk_id", UUID.class))
                            .indexGenerationId(rs.getObject("index_generation_id", UUID.class))
                            .sourceRevision(rs.getLong("source_revision"))
                            .embeddingModel(rs.getString("embedding_model"))
                            .outputDimensionality(rs.getInt("output_dimensionality"))
                            .content(content)
                            .contentHash(sha256Hex(content))
                            .estimatedTokens(rs.getInt("estimated_tokens"))
                            .attemptCount(rs.getInt("attempt_count"))
                            .workerToken(rs.getObject("worker_token", UUID.class))
                            .build();
                },
                workerToken);
    }

    /**
     * Releases a claimed task back to PENDING without consuming an attempt (used when TPM bucket
     * throttles before dispatching to ai-service).
     */
    @Transactional
    public void releaseThrottledTask(UUID outboxId, UUID workerToken, Instant nextRetryAt) {
        jdbcTemplate.update(
                """
                UPDATE contextflow.embedding_outbox
                SET status = 'PENDING',
                    worker_token = NULL,
                    lease_expires_at = NULL,
                    attempt_count = GREATEST(0, attempt_count - 1),
                    next_retry_at = ?,
                    updated_at = now()
                WHERE id = ? AND worker_token = ? AND status = 'IN_PROGRESS'
                """,
                Timestamp.from(nextRetryAt),
                outboxId,
                workerToken);
    }

    /**
     * Enforces all 5 mandatory vector-write guards atomically before writing the 1536d embedding:
     * (1) Project not DELETING/DELETED, (2) knowledge_item still active/not soft-deleted, (3) chunk
     * source_revision & content hash unchanged, (4) index_generation still ACTIVE, (5) outbox item
     * still IN_PROGRESS with matching worker_token (not CANCELLED).
     *
     * @return true if vector was written and outbox marked COMPLETED; false if any guard failed and
     *     outbox was cancelled (or already cancelled).
     */
    @Transactional
    public boolean verifyAndWriteChunkEmbedding(ClaimedOutboxTask task, List<Double> vector) {
        List<GuardSnapshot> snapshots =
                jdbcTemplate.query(
                        """
                        SELECT p.status AS project_status,
                               ki.is_active AS item_active,
                               ki.soft_deleted_at AS item_deleted_at,
                               kc.is_active AS chunk_active,
                               kc.source_revision AS chunk_revision,
                               kc.content AS chunk_content,
                               ig.status AS gen_status,
                               eo.status AS outbox_status,
                               eo.worker_token AS outbox_worker_token
                        FROM contextflow.embedding_outbox eo
                        JOIN contextflow.projects p ON p.id = eo.project_id
                        JOIN contextflow.knowledge_items ki
                          ON ki.project_id = eo.project_id AND ki.id = eo.knowledge_item_id
                        JOIN contextflow.knowledge_chunks kc
                          ON kc.project_id = eo.project_id AND kc.id = eo.chunk_id
                        JOIN contextflow.index_generations ig
                          ON ig.project_id = eo.project_id AND ig.id = eo.index_generation_id
                        WHERE eo.id = ?
                        FOR UPDATE OF eo
                        """,
                        (rs, rowNum) ->
                                new GuardSnapshot(
                                        rs.getString("project_status"),
                                        rs.getBoolean("item_active"),
                                        rs.getTimestamp("item_deleted_at"),
                                        rs.getBoolean("chunk_active"),
                                        rs.getLong("chunk_revision"),
                                        rs.getString("chunk_content"),
                                        rs.getString("gen_status"),
                                        rs.getString("outbox_status"),
                                        rs.getObject("outbox_worker_token", UUID.class)),
                        task.getOutboxId());

        if (snapshots.isEmpty()) {
            return false;
        }
        GuardSnapshot snap = snapshots.getFirst();
        String failureReason = evaluateGuards(task, snap);
        if (failureReason != null) {
            if (!"CANCELLED".equals(snap.outboxStatus)) {
                jdbcTemplate.update(
                        """
                        UPDATE contextflow.embedding_outbox
                        SET status = 'CANCELLED',
                            worker_token = NULL,
                            lease_expires_at = NULL,
                            error_category = 'SOURCE_INVALIDATED',
                            last_error_code = ?,
                            updated_at = now()
                        WHERE id = ?
                        """,
                        failureReason,
                        task.getOutboxId());
            }
            return false;
        }

        String vectorLiteral = toPgVectorLiteral(vector);
        jdbcTemplate.update(
                """
                UPDATE contextflow.knowledge_chunks
                SET embedding = ?::public.vector
                WHERE id = ? AND project_id = ?
                """,
                vectorLiteral,
                task.getChunkId(),
                task.getProjectId());

        jdbcTemplate.update(
                """
                UPDATE contextflow.embedding_outbox
                SET status = 'COMPLETED',
                    worker_token = NULL,
                    lease_expires_at = NULL,
                    error_category = NULL,
                    last_error_code = NULL,
                    updated_at = now()
                WHERE id = ? AND worker_token = ? AND status = 'IN_PROGRESS'
                """,
                task.getOutboxId(),
                task.getWorkerToken());
        return true;
    }

    @Transactional
    public void markOutboxFailure(
            UUID outboxId,
            UUID workerToken,
            OutboxStatus targetStatus,
            ProviderErrorCategory category,
            String errorCode,
            Instant nextRetryAt) {
        Timestamp retryTs = Timestamp.from(nextRetryAt != null ? nextRetryAt : Instant.now());
        jdbcTemplate.update(
                """
                UPDATE contextflow.embedding_outbox
                SET status = ?,
                    error_category = ?,
                    last_error_code = ?,
                    next_retry_at = ?,
                    worker_token = NULL,
                    lease_expires_at = NULL,
                    updated_at = now()
                WHERE id = ? AND worker_token = ? AND status = 'IN_PROGRESS'
                """,
                targetStatus.name(),
                category != null ? category.name() : null,
                errorCode,
                retryTs,
                outboxId,
                workerToken);
    }

    private static String evaluateGuards(ClaimedOutboxTask task, GuardSnapshot snap) {
        // Guard 1: Project not DELETING/DELETED
        if ("DELETING".equals(snap.projectStatus) || "DELETED".equals(snap.projectStatus)) {
            return "PROJECT_DELETING_OR_DELETED";
        }
        // Guard 2: knowledge_item still active & not soft-deleted
        if (!snap.itemActive || snap.itemDeletedAt != null || !snap.chunkActive) {
            return "KNOWLEDGE_ITEM_INACTIVE";
        }
        // Guard 3: chunk source_revision and content_hash unchanged
        if (snap.chunkRevision != task.getSourceRevision()
                || !sha256Hex(snap.chunkContent).equals(task.getContentHash())) {
            return "CHUNK_CONTENT_OR_REVISION_CHANGED";
        }
        // Guard 4: index_generation still ACTIVE
        if (!"ACTIVE".equals(snap.genStatus)) {
            return "INDEX_GENERATION_NOT_ACTIVE";
        }
        // Guard 5: outbox item still IN_PROGRESS with matching worker_token (not CANCELLED)
        if (!"IN_PROGRESS".equals(snap.outboxStatus)
                || !task.getWorkerToken().equals(snap.outboxWorkerToken)) {
            return "OUTBOX_CANCELLED_OR_LEASE_LOST";
        }
        return null;
    }

    public static String toPgVectorLiteral(List<Double> vector) {
        if (vector == null || vector.size() != EXPECTED_EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException(
                    "Embedding vector must have exactly "
                            + EXPECTED_EMBEDDING_DIMENSION
                            + " finite dimensions.");
        }
        StringBuilder sb = new StringBuilder(vector.size() * 10);
        sb.append('[');
        for (int i = 0; i < vector.size(); i++) {
            Double val = vector.get(i);
            if (val == null || !Double.isFinite(val)) {
                throw new IllegalArgumentException(
                        "Embedding vector contains non-finite value at index " + i);
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(val);
        }
        sb.append(']');
        return sb.toString();
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest =
                    md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static final class ScopeRow {
        private final UUID projectId;
        private final UUID indexGenerationId;

        private ScopeRow(UUID projectId, UUID indexGenerationId) {
            this.projectId = projectId;
            this.indexGenerationId = indexGenerationId;
        }
    }

    private static final class GuardSnapshot {
        private final String projectStatus;
        private final boolean itemActive;
        private final Timestamp itemDeletedAt;
        private final boolean chunkActive;
        private final long chunkRevision;
        private final String chunkContent;
        private final String genStatus;
        private final String outboxStatus;
        private final UUID outboxWorkerToken;

        private GuardSnapshot(
                String projectStatus,
                boolean itemActive,
                Timestamp itemDeletedAt,
                boolean chunkActive,
                long chunkRevision,
                String chunkContent,
                String genStatus,
                String outboxStatus,
                UUID outboxWorkerToken) {
            this.projectStatus = projectStatus;
            this.itemActive = itemActive;
            this.itemDeletedAt = itemDeletedAt;
            this.chunkActive = chunkActive;
            this.chunkRevision = chunkRevision;
            this.chunkContent = chunkContent;
            this.genStatus = genStatus;
            this.outboxStatus = outboxStatus;
            this.outboxWorkerToken = outboxWorkerToken;
        }
    }
}

package com.contextflow.retrieval.repository;

import com.contextflow.knowledge.repository.KnowledgeIngestionRepository;
import com.contextflow.retrieval.dto.RetrievedChunkHit;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL 16 hybrid retrieval repository enforcing strict project_id, ACTIVE index_generation,
 * and active knowledge_item isolation across Vector Search (pgvector HNSW relaxed_order),
 * Vietnamese FTS (public.cf_vi), and Exact Technical Terms Lookup (GIN text[]).
 */
@Repository
@RequiredArgsConstructor
public class HybridRetrievalRepository {
    public static final String BRANCH_VECTOR = "VECTOR_SEARCH";
    public static final String BRANCH_KEYWORD = "KEYWORD_SEARCH";
    public static final String BRANCH_EXACT = "EXACT_LOOKUP";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Branch 1 — VECTOR_SEARCH: sets {@code SET LOCAL hnsw.iterative_scan = relaxed_order} inside a
     * read-only transaction before querying pgvector cosine distance.
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunkHit> searchVector(
            UUID projectId, UUID activeGenerationId, List<Double> queryVector, int limit) {
        jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
        String vectorLiteral = KnowledgeIngestionRepository.toPgVectorLiteral(queryVector);
        return jdbcTemplate.query(
                """
                SELECT kc.id AS chunk_id,
                       kc.knowledge_item_id,
                       ki.source_turn_id,
                       ki.promoted_from_quote_ref_id,
                       ki.item_type,
                       kc.source_type,
                       ki.title,
                       kc.content,
                       kc.source_revision,
                       kc.created_at,
                       (1.0 - (kc.embedding <=> ?::public.vector)) AS score
                FROM contextflow.knowledge_chunks kc
                JOIN contextflow.knowledge_items ki
                  ON ki.project_id = kc.project_id AND ki.id = kc.knowledge_item_id
                JOIN contextflow.index_generations ig
                  ON ig.project_id = kc.project_id AND ig.id = kc.index_generation_id
                JOIN contextflow.projects p
                  ON p.id = kc.project_id
                WHERE kc.project_id = ?
                  AND kc.index_generation_id = ?
                  AND kc.is_active = true
                  AND kc.embedding IS NOT NULL
                  AND ki.is_active = true
                  AND ki.soft_deleted_at IS NULL
                  AND ig.status = 'ACTIVE'
                  AND p.status NOT IN ('DELETING', 'DELETED')
                ORDER BY kc.embedding <=> ?::public.vector ASC, kc.created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> mapHit(rs, BRANCH_VECTOR),
                vectorLiteral,
                projectId,
                activeGenerationId,
                vectorLiteral,
                limit);
    }

    /** Branch 2 — KEYWORD_SEARCH: Vietnamese FTS on {@code public.cf_vi} GIN index. */
    @Transactional(readOnly = true)
    public List<RetrievedChunkHit> searchKeyword(
            UUID projectId, UUID activeGenerationId, String queryText, int limit) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                WITH q AS (
                    SELECT websearch_to_tsquery('public.cf_vi'::regconfig, ?) AS tsq
                )
                SELECT kc.id AS chunk_id,
                       kc.knowledge_item_id,
                       ki.source_turn_id,
                       ki.promoted_from_quote_ref_id,
                       ki.item_type,
                       kc.source_type,
                       ki.title,
                       kc.content,
                       kc.source_revision,
                       kc.created_at,
                       ts_rank_cd(kc.search_document, q.tsq)::double precision AS score
                FROM contextflow.knowledge_chunks kc
                CROSS JOIN q
                JOIN contextflow.knowledge_items ki
                  ON ki.project_id = kc.project_id AND ki.id = kc.knowledge_item_id
                JOIN contextflow.index_generations ig
                  ON ig.project_id = kc.project_id AND ig.id = kc.index_generation_id
                JOIN contextflow.projects p
                  ON p.id = kc.project_id
                WHERE kc.project_id = ?
                  AND kc.index_generation_id = ?
                  AND kc.is_active = true
                  AND ki.is_active = true
                  AND ki.soft_deleted_at IS NULL
                  AND ig.status = 'ACTIVE'
                  AND p.status NOT IN ('DELETING', 'DELETED')
                  AND kc.search_document @@ q.tsq
                ORDER BY score DESC, kc.created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> mapHit(rs, BRANCH_KEYWORD),
                queryText,
                projectId,
                activeGenerationId,
                limit);
    }

    /** Branch 3 — EXACT_LOOKUP: exact term overlap on {@code technical_terms} GIN index. */
    @Transactional(readOnly = true)
    public List<RetrievedChunkHit> searchExactTerms(
            UUID projectId, UUID activeGenerationId, List<String> terms, int limit) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        String[] termsArray = terms.toArray(String[]::new);
        return jdbcTemplate.query(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    """
                                    SELECT kc.id AS chunk_id,
                                           kc.knowledge_item_id,
                                           ki.source_turn_id,
                                           ki.promoted_from_quote_ref_id,
                                           ki.item_type,
                                           kc.source_type,
                                           ki.title,
                                           kc.content,
                                           kc.source_revision,
                                           kc.created_at,
                                           (
                                               SELECT count(*)::double precision
                                               FROM unnest(kc.technical_terms) t
                                               WHERE t = ANY(?::text[])
                                           ) AS score
                                    FROM contextflow.knowledge_chunks kc
                                    JOIN contextflow.knowledge_items ki
                                      ON ki.project_id = kc.project_id AND ki.id = kc.knowledge_item_id
                                    JOIN contextflow.index_generations ig
                                      ON ig.project_id = kc.project_id AND ig.id = kc.index_generation_id
                                    JOIN contextflow.projects p
                                      ON p.id = kc.project_id
                                    WHERE kc.project_id = ?
                                      AND kc.index_generation_id = ?
                                      AND kc.is_active = true
                                      AND ki.is_active = true
                                      AND ki.soft_deleted_at IS NULL
                                      AND ig.status = 'ACTIVE'
                                      AND p.status NOT IN ('DELETING', 'DELETED')
                                      AND kc.technical_terms && ?::text[]
                                    ORDER BY score DESC, kc.created_at DESC
                                    LIMIT ?
                                    """);
                    Array sqlArr1 = connection.createArrayOf("text", termsArray);
                    Array sqlArr2 = connection.createArrayOf("text", termsArray);
                    ps.setArray(1, sqlArr1);
                    ps.setObject(2, projectId);
                    ps.setObject(3, activeGenerationId);
                    ps.setArray(4, sqlArr2);
                    ps.setInt(5, limit);
                    return ps;
                },
                (rs, rowNum) -> mapHit(rs, BRANCH_EXACT));
    }

    /** Exposes the active transaction's hnsw.iterative_scan setting for verification. */
    @Transactional(readOnly = true)
    public String readHnswIterativeScanAfterSetting() {
        jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
        return jdbcTemplate.queryForObject("SHOW hnsw.iterative_scan", String.class);
    }

    private static RetrievedChunkHit mapHit(ResultSet rs, String branchName) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        return RetrievedChunkHit.builder()
                .chunkId(rs.getObject("chunk_id", UUID.class))
                .knowledgeItemId(rs.getObject("knowledge_item_id", UUID.class))
                .sourceTurnId(rs.getObject("source_turn_id", UUID.class))
                .promotedFromQuoteRefId(rs.getObject("promoted_from_quote_ref_id", UUID.class))
                .itemType(rs.getString("item_type"))
                .sourceType(rs.getString("source_type"))
                .title(rs.getString("title"))
                .content(rs.getString("content"))
                .sourceRevision(String.valueOf(rs.getLong("source_revision")))
                .createdAt(createdTs != null ? createdTs.toInstant() : null)
                .rawScore(rs.getDouble("score"))
                .branchName(branchName)
                .build();
    }
}

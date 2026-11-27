package com.contextflow.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.contextflow.identity.demo.LocalDemoIdentity;
import com.contextflow.knowledge.client.EmbeddingClientException;
import com.contextflow.knowledge.dto.EmbeddingBatchRequest;
import com.contextflow.knowledge.dto.EmbeddingBatchResponse;
import com.contextflow.knowledge.dto.KnowledgeIngestionResult;
import com.contextflow.knowledge.dto.OutboxBatchOutcome;
import com.contextflow.knowledge.dto.ProviderErrorCategory;
import com.contextflow.knowledge.dto.TurnContentBlockDto;
import com.contextflow.knowledge.dto.TurnIngestionRequest;
import com.contextflow.knowledge.dto.TurnQuoteExclusionDto;
import com.contextflow.knowledge.port.EmbeddingClient;
import com.contextflow.knowledge.port.EmbeddingCredentialProvider;
import com.contextflow.knowledge.service.IndexGenerationBootstrap;
import com.contextflow.knowledge.service.KnowledgeIngestionService;
import com.contextflow.knowledge.worker.EmbeddingOutboxWorker;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "CF_LOCAL_ENV_IMPORT=optional:classpath:/test-no-local-env.properties")
@ActiveProfiles("local-demo")
@Testcontainers
class KnowledgeIngestionAndOutboxIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.1-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", POSTGRES::getUsername);
        properties.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IndexGenerationBootstrap indexGenerationBootstrap;
    @Autowired private KnowledgeIngestionService ingestionService;
    @Autowired private EmbeddingOutboxWorker outboxWorker;

    @MockitoBean private EmbeddingClient embeddingClient;
    @MockitoBean private EmbeddingCredentialProvider credentialProvider;

    private UUID projectId;
    private UUID generationId;

    @BeforeEach
    void setUpProject() {
        projectId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.projects (id, owner_user_id, name, status)
                VALUES (?, ?, 'KB Ingestion Test Project', 'ACTIVE')
                """,
                projectId,
                LocalDemoIdentity.USER_ID);
        generationId = indexGenerationBootstrap.initializeForProject(projectId);
        when(credentialProvider.resolvePlatformCredential(any()))
                .thenReturn(Optional.of("AIzaSyIntegrationTestPlatformKey"));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS trg_fail_outbox_test ON contextflow.embedding_outbox");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS contextflow.fail_outbox_test_fn()");
        jdbcTemplate.update("DELETE FROM contextflow.projects");
    }

    @Test
    void atomicIngestionRollsBackItemsAndChunksWhenOutboxInsertFails() {
        jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION contextflow.fail_outbox_test_fn()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Simulated outbox write failure';
                END;
                $$;
                CREATE TRIGGER trg_fail_outbox_test
                BEFORE INSERT ON contextflow.embedding_outbox
                FOR EACH ROW EXECUTE FUNCTION contextflow.fail_outbox_test_fn();
                """);

        TurnIngestionRequest req =
                TurnIngestionRequest.builder()
                        .projectId(projectId)
                        .conversationId(UUID.randomUUID())
                        .mainStageId(UUID.randomUUID())
                        .turnId(UUID.randomUUID())
                        .userContentBlocks(
                                List.of(TurnContentBlockDto.textBlock("Atomic rollback probe")))
                        .assistantText("Assistant response that must roll back completely.")
                        .build();

        assertThrows(RuntimeException.class, () -> ingestionService.ingestCommittedMainTurn(req));

        Integer itemCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM contextflow.knowledge_items WHERE project_id = ?",
                        Integer.class,
                        projectId);
        Integer chunkCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM contextflow.knowledge_chunks WHERE project_id = ?",
                        Integer.class,
                        projectId);
        Integer outboxCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM contextflow.embedding_outbox WHERE project_id = ?",
                        Integer.class,
                        projectId);

        assertEquals(0, itemCount);
        assertEquals(0, chunkCount);
        assertEquals(0, outboxCount);
    }

    @Test
    void preChunkingExclusionOmitsExcludedSubBranchQuoteAndPopulatesFtsAndTerms() {
        TurnIngestionRequest req =
                TurnIngestionRequest.builder()
                        .projectId(projectId)
                        .conversationId(UUID.randomUUID())
                        .mainStageId(UUID.randomUUID())
                        .turnId(UUID.randomUUID())
                        .userContentBlocks(
                                List.of(
                                        TurnContentBlockDto.textBlock("Kiểm tra CF-DEC-038 "),
                                        TurnContentBlockDto.quoteRefBlock("q_excluded"),
                                        TurnContentBlockDto.quoteRefBlock("q_allowed")))
                        .quoteSnapshots(
                                List.of(
                                        TurnQuoteExclusionDto.builder()
                                                .quoteRefId("q_excluded")
                                                .snapshotText(
                                                        "UNMERGED_BRANCH_SECRET_SHOULD_NOT_INDEX")
                                                .excludeFromAutoKb(true)
                                                .build(),
                                        TurnQuoteExclusionDto.builder()
                                                .quoteRefId("q_allowed")
                                                .snapshotText("ALLOWED_MAIN_SNAPSHOT")
                                                .excludeFromAutoKb(false)
                                                .build()))
                        .assistantText("Đã chốt bảng embedding_outbox cho luồng indexing.")
                        .build();

        KnowledgeIngestionResult result = ingestionService.ingestCommittedMainTurn(req);
        assertFalse(result.isSkipped());
        assertEquals(1, result.getExcludedQuoteCount());
        assertEquals(1, result.getChunkIds().size());
        assertEquals(1, result.getOutboxIds().size());

        String chunkContent =
                jdbcTemplate.queryForObject(
                        "SELECT content FROM contextflow.knowledge_chunks WHERE id = ?",
                        String.class,
                        result.getChunkIds().getFirst());
        assertNotNull(chunkContent);
        assertFalse(chunkContent.contains("UNMERGED_BRANCH_SECRET_SHOULD_NOT_INDEX"));
        assertTrue(chunkContent.contains("ALLOWED_MAIN_SNAPSHOT"));

        Boolean hasSearchDoc =
                jdbcTemplate.queryForObject(
                        "SELECT search_document IS NOT NULL FROM contextflow.knowledge_chunks WHERE id = ?",
                        Boolean.class,
                        result.getChunkIds().getFirst());
        assertEquals(Boolean.TRUE, hasSearchDoc);

        Boolean hasTerm =
                jdbcTemplate.queryForObject(
                        "SELECT 'CF-DEC-038' = ANY(technical_terms) AND 'embedding_outbox' = ANY(technical_terms) "
                                + "FROM contextflow.knowledge_chunks WHERE id = ?",
                        Boolean.class,
                        result.getChunkIds().getFirst());
        assertEquals(Boolean.TRUE, hasTerm);
    }

    @Test
    void workerWrites1536dVectorOnHappyPathAndCompletesOutbox() {
        KnowledgeIngestionResult ingested =
                ingestSimpleTurn("Nội dung cần sinh vector 1536 chiều cho pgvector.");
        List<Double> vector = Collections.nCopies(1536, 0.015625);
        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenReturn(
                        EmbeddingBatchResponse.builder()
                                .dispatched(true)
                                .vectors(List.of(vector))
                                .build());

        OutboxBatchOutcome outcome = outboxWorker.processNextBatch();
        assertEquals(1, outcome.getClaimedCount());
        assertEquals(1, outcome.getCompletedCount());
        assertEquals(0, outcome.getCancelledCount());

        Boolean hasEmbedding =
                jdbcTemplate.queryForObject(
                        "SELECT embedding IS NOT NULL FROM contextflow.knowledge_chunks WHERE id = ?",
                        Boolean.class,
                        ingested.getChunkIds().getFirst());
        String status =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM contextflow.embedding_outbox WHERE id = ?",
                        String.class,
                        ingested.getOutboxIds().getFirst());
        assertEquals(Boolean.TRUE, hasEmbedding);
        assertEquals("COMPLETED", status);
    }

    @Test
    void fiveVectorWriteGuardsCancelOutboxAndSkipWriteWhenProjectDeletingOrItemDeleted() {
        // Case A: Project transitions to DELETING while worker is calling /embeddings
        KnowledgeIngestionResult ingestedA = ingestSimpleTurn("Guard test project deleting");
        List<Double> vector = Collections.nCopies(1536, 0.02);
        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenAnswer(inv -> {
                    jdbcTemplate.update(
                            "UPDATE contextflow.projects SET status = 'DELETING' WHERE id = ?",
                            projectId);
                    return EmbeddingBatchResponse.builder()
                            .dispatched(true)
                            .vectors(List.of(vector))
                            .build();
                });

        OutboxBatchOutcome outcomeA = outboxWorker.processNextBatch();
        assertEquals(1, outcomeA.getCancelledCount());
        assertEquals(0, outcomeA.getCompletedCount());
        assertNull(
                jdbcTemplate.queryForObject(
                        "SELECT embedding::text FROM contextflow.knowledge_chunks WHERE id = ?",
                        String.class,
                        ingestedA.getChunkIds().getFirst()));
        assertEquals(
                "CANCELLED",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM contextflow.embedding_outbox WHERE id = ?",
                        String.class,
                        ingestedA.getOutboxIds().getFirst()));

        // Restore project ACTIVE for Case B: knowledge_item soft-deleted mid-flight
        jdbcTemplate.update(
                "UPDATE contextflow.projects SET status = 'ACTIVE' WHERE id = ?", projectId);
        KnowledgeIngestionResult ingestedB = ingestSimpleTurn("Guard test soft deleted item");
        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenAnswer(inv -> {
                    jdbcTemplate.update(
                            """
                            UPDATE contextflow.knowledge_items
                            SET is_active = false, soft_deleted_at = now()
                            WHERE id = ?
                            """,
                            ingestedB.getKnowledgeItemId());
                    return EmbeddingBatchResponse.builder()
                            .dispatched(true)
                            .vectors(List.of(vector))
                            .build();
                });

        OutboxBatchOutcome outcomeB = outboxWorker.processNextBatch();
        assertEquals(1, outcomeB.getCancelledCount());
        assertNull(
                jdbcTemplate.queryForObject(
                        "SELECT embedding::text FROM contextflow.knowledge_chunks WHERE id = ?",
                        String.class,
                        ingestedB.getChunkIds().getFirst()));
        assertEquals(
                "CANCELLED",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM contextflow.embedding_outbox WHERE id = ?",
                        String.class,
                        ingestedB.getOutboxIds().getFirst()));

        // Case C: index_generation becomes SUPERSEDED mid-flight
        KnowledgeIngestionResult ingestedC = ingestSimpleTurn("Guard test superseded generation");
        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenAnswer(inv -> {
                    jdbcTemplate.update(
                            "UPDATE contextflow.index_generations SET status = 'SUPERSEDED' WHERE id = ?",
                            generationId);
                    return EmbeddingBatchResponse.builder()
                            .dispatched(true)
                            .vectors(List.of(vector))
                            .build();
                });

        OutboxBatchOutcome outcomeC = outboxWorker.processNextBatch();
        assertEquals(1, outcomeC.getCancelledCount());
        assertNull(
                jdbcTemplate.queryForObject(
                        "SELECT embedding::text FROM contextflow.knowledge_chunks WHERE id = ?",
                        String.class,
                        ingestedC.getChunkIds().getFirst()));

        // Restore generation ACTIVE for Case D (Guard 3: content changed mid-flight) and Case E
        // (Guard 5: outbox CANCELLED mid-flight)
        jdbcTemplate.update(
                "UPDATE contextflow.index_generations SET status = 'ACTIVE' WHERE id = ?",
                generationId);
        KnowledgeIngestionResult ingestedD = ingestSimpleTurn("Guard 3 content changed mid-flight");
        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenAnswer(inv -> {
                    jdbcTemplate.update(
                            "UPDATE contextflow.knowledge_chunks SET content = 'MUTATED_CONTENT' WHERE id = ?",
                            ingestedD.getChunkIds().getFirst());
                    return EmbeddingBatchResponse.builder()
                            .dispatched(true)
                            .vectors(List.of(vector))
                            .build();
                });
        OutboxBatchOutcome outcomeD = outboxWorker.processNextBatch();
        assertEquals(1, outcomeD.getCancelledCount());
        assertNull(
                jdbcTemplate.queryForObject(
                        "SELECT embedding::text FROM contextflow.knowledge_chunks WHERE id = ?",
                        String.class,
                        ingestedD.getChunkIds().getFirst()));

        KnowledgeIngestionResult ingestedE =
                ingestSimpleTurn("Guard 5 outbox cancelled mid-flight");
        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenAnswer(inv -> {
                    jdbcTemplate.update(
                            "UPDATE contextflow.embedding_outbox SET status = 'CANCELLED', worker_token = NULL, lease_expires_at = NULL WHERE id = ?",
                            ingestedE.getOutboxIds().getFirst());
                    return EmbeddingBatchResponse.builder()
                            .dispatched(true)
                            .vectors(List.of(vector))
                            .build();
                });
        OutboxBatchOutcome outcomeE = outboxWorker.processNextBatch();
        assertEquals(1, outcomeE.getCancelledCount());
        assertNull(
                jdbcTemplate.queryForObject(
                        "SELECT embedding::text FROM contextflow.knowledge_chunks WHERE id = ?",
                        String.class,
                        ingestedE.getChunkIds().getFirst()));
    }

    @Test
    void ingestionIsIdempotentForDuplicateCommittedMainTurnCalls() {
        UUID turnId = UUID.randomUUID();
        TurnIngestionRequest req =
                TurnIngestionRequest.builder()
                        .projectId(projectId)
                        .conversationId(UUID.randomUUID())
                        .mainStageId(UUID.randomUUID())
                        .turnId(turnId)
                        .userContentBlocks(
                                List.of(TurnContentBlockDto.textBlock("Idempotent turn payload")))
                        .assistantText("Idempotent assistant response")
                        .build();

        KnowledgeIngestionResult first = ingestionService.ingestCommittedMainTurn(req);
        KnowledgeIngestionResult second = ingestionService.ingestCommittedMainTurn(req);

        assertFalse(first.isSkipped());
        assertTrue(second.isSkipped());
        assertEquals(first.getKnowledgeItemId(), second.getKnowledgeItemId());
        Integer itemCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM contextflow.knowledge_items WHERE project_id = ? AND source_turn_id = ?",
                        Integer.class,
                        projectId,
                        turnId);
        assertEquals(1, itemCount);
    }

    @Test
    void workerMapsRateLimitRetryableAndHardQuotaBlockedAccurately() {
        // 1. RATE_LIMIT_RETRYABLE -> PENDING + attempt_count=1 + next_retry_at pushed forward
        KnowledgeIngestionResult ingested429 = ingestSimpleTurn("Rate limit 429 test");
        Instant beforeCall = Instant.now();
        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenThrow(
                        new EmbeddingClientException(
                                ProviderErrorCategory.RATE_LIMIT_RETRYABLE,
                                "PROVIDER_RATE_LIMITED",
                                true,
                                15,
                                true,
                                "RPM exceeded"));

        OutboxBatchOutcome outcome429 = outboxWorker.processNextBatch();
        assertEquals(1, outcome429.getRetriedCount());
        assertEquals(0, outcome429.getBlockedCount());

        var row429 =
                jdbcTemplate.queryForMap(
                        "SELECT status, error_category, attempt_count, next_retry_at "
                                + "FROM contextflow.embedding_outbox WHERE id = ?",
                        ingested429.getOutboxIds().getFirst());
        assertEquals("PENDING", row429.get("status"));
        assertEquals("RATE_LIMIT_RETRYABLE", row429.get("error_category"));
        assertEquals(1, ((Number) row429.get("attempt_count")).intValue());
        Instant nextRetryAt = ((Timestamp) row429.get("next_retry_at")).toInstant();
        assertTrue(nextRetryAt.isAfter(beforeCall.plusSeconds(10)));

        // Cancel the first row so the next batch picks the hard-quota row cleanly
        jdbcTemplate.update(
                "UPDATE contextflow.embedding_outbox SET status = 'CANCELLED' WHERE id = ?",
                ingested429.getOutboxIds().getFirst());

        // 2. HARD_QUOTA_BLOCKED -> stops retrying immediately
        KnowledgeIngestionResult ingestedQuota = ingestSimpleTurn("Hard quota blocked test");
        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenThrow(
                        new EmbeddingClientException(
                                ProviderErrorCategory.HARD_QUOTA_BLOCKED,
                                "PROVIDER_QUOTA_EXHAUSTED",
                                false,
                                null,
                                true,
                                "Daily RPD exhausted"));

        OutboxBatchOutcome outcomeQuota = outboxWorker.processNextBatch();
        assertEquals(1, outcomeQuota.getBlockedCount());
        assertEquals(0, outcomeQuota.getRetriedCount());

        var rowQuota =
                jdbcTemplate.queryForMap(
                        "SELECT status, error_category, last_error_code "
                                + "FROM contextflow.embedding_outbox WHERE id = ?",
                        ingestedQuota.getOutboxIds().getFirst());
        assertEquals("HARD_QUOTA_BLOCKED", rowQuota.get("status"));
        assertEquals("HARD_QUOTA_BLOCKED", rowQuota.get("error_category"));
        assertEquals("PROVIDER_QUOTA_EXHAUSTED", rowQuota.get("last_error_code"));
    }

    private KnowledgeIngestionResult ingestSimpleTurn(String text) {
        return ingestionService.ingestCommittedMainTurn(
                TurnIngestionRequest.builder()
                        .projectId(projectId)
                        .conversationId(UUID.randomUUID())
                        .mainStageId(UUID.randomUUID())
                        .turnId(UUID.randomUUID())
                        .userContentBlocks(List.of(TurnContentBlockDto.textBlock(text)))
                        .assistantText("Phản hồi xác nhận: " + text)
                        .build());
    }
}

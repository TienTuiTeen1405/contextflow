package com.contextflow.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.contextflow.context.dto.DegradedComponentStatus;
import com.contextflow.context.dto.DegradedComponentType;
import com.contextflow.context.dto.EvidenceSufficiency;
import com.contextflow.context.dto.KnowledgeRetrievalRequest;
import com.contextflow.context.dto.KnowledgeRetrievalResult;
import com.contextflow.context.dto.RetrievalStatus;
import com.contextflow.context.port.KnowledgeRetrievalPort;
import com.contextflow.identity.demo.LocalDemoIdentity;
import com.contextflow.knowledge.client.EmbeddingClientException;
import com.contextflow.knowledge.dto.EmbeddingBatchRequest;
import com.contextflow.knowledge.dto.EmbeddingBatchResponse;
import com.contextflow.knowledge.dto.KnowledgeIngestionResult;
import com.contextflow.knowledge.dto.ProviderErrorCategory;
import com.contextflow.knowledge.dto.TurnContentBlockDto;
import com.contextflow.knowledge.dto.TurnIngestionRequest;
import com.contextflow.knowledge.port.EmbeddingClient;
import com.contextflow.knowledge.port.EmbeddingCredentialProvider;
import com.contextflow.knowledge.repository.KnowledgeIngestionRepository;
import com.contextflow.knowledge.service.IndexGenerationBootstrap;
import com.contextflow.knowledge.service.KnowledgeIngestionService;
import com.contextflow.retrieval.repository.HybridRetrievalRepository;
import com.contextflow.retrieval.service.impl.HybridKnowledgeRetrievalService;
import java.util.ArrayList;
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
class HybridRetrievalIntegrationTest {
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
    @Autowired private HybridRetrievalRepository retrievalRepository;
    @Autowired private KnowledgeRetrievalPort knowledgeRetrievalPort;

    @MockitoBean private EmbeddingClient embeddingClient;
    @MockitoBean private EmbeddingCredentialProvider credentialProvider;

    private UUID projectA;
    private UUID projectB;
    private UUID genA;

    @BeforeEach
    void setUpProjects() {
        projectA = UUID.randomUUID();
        projectB = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contextflow.projects (id, owner_user_id, name, status) VALUES (?, ?, 'Project A', 'ACTIVE')",
                projectA,
                LocalDemoIdentity.USER_ID);
        jdbcTemplate.update(
                "INSERT INTO contextflow.projects (id, owner_user_id, name, status) VALUES (?, ?, 'Project B', 'ACTIVE')",
                projectB,
                LocalDemoIdentity.USER_ID);
        genA = indexGenerationBootstrap.initializeForProject(projectA);
        indexGenerationBootstrap.initializeForProject(projectB);

        when(credentialProvider.resolvePlatformCredential(any()))
                .thenReturn(Optional.of("AIzaSyHybridRetrievalTestKey"));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM contextflow.projects");
    }

    @Test
    void wiresHybridServiceAsPrimaryKnowledgeRetrievalPortAndSetsHnswRelaxedOrder() {
        assertInstanceOf(HybridKnowledgeRetrievalService.class, knowledgeRetrievalPort);
        assertEquals("relaxed_order", retrievalRepository.readHnswIterativeScanAfterSetting());
    }

    @Test
    void enforcesStrictIsolationAcrossProjectsSupersededGenerationsAndSoftDeletedItems() {
        List<Double> unitVec = basisVector(0);
        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenReturn(
                        EmbeddingBatchResponse.builder()
                                .dispatched(true)
                                .vectors(List.of(unitVec))
                                .build());

        // 1. Active chunk in Project A (MUST be returned)
        KnowledgeIngestionResult activeA =
                ingestAndEmbed(
                        projectA,
                        "Tài liệu kiến trúc chuẩn cho Project A về kiến trúc hybrid search.",
                        unitVec);

        // 2. Soft-deleted item in Project A (MUST NOT be returned)
        KnowledgeIngestionResult deletedA =
                ingestAndEmbed(
                        projectA,
                        "Tài liệu kiến trúc đã xóa mềm trong Project A về hybrid search.",
                        unitVec);
        jdbcTemplate.update(
                "UPDATE contextflow.knowledge_items SET is_active = false, soft_deleted_at = now() WHERE id = ?",
                deletedA.getKnowledgeItemId());

        // 3. Active chunk in Project B (MUST NOT leak into Project A)
        ingestAndEmbed(
                projectB, "Tài liệu bí mật của Project B về kiến trúc hybrid search.", unitVec);

        // 4. Chunk in a SUPERSEDED index_generation of Project A (MUST NOT be returned)
        UUID supersededGenId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.index_generations
                    (id, project_id, embedding_model, output_dimensionality, status)
                VALUES (?, ?, 'gemini-embedding-2', 1536, 'SUPERSEDED')
                """,
                supersededGenId,
                projectA);
        UUID supersededChunkId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO contextflow.knowledge_chunks (
                    id, project_id, knowledge_item_id, index_generation_id, source_type,
                    source_revision, chunk_index, content, search_text, technical_terms,
                    embedding, is_active
                )
                VALUES (?, ?, ?, ?, 'MAIN_TURN', 1, 99,
                        'Old superseded generation hybrid search chunk',
                        'Old superseded generation hybrid search chunk',
                        ARRAY['CF-OLD-GEN']::text[], ?::public.vector, true)
                """,
                supersededChunkId,
                projectA,
                activeA.getKnowledgeItemId(),
                supersededGenId,
                KnowledgeIngestionRepository.toPgVectorLiteral(unitVec));

        KnowledgeRetrievalResult result =
                knowledgeRetrievalPort.retrieve(
                        KnowledgeRetrievalRequest.builder()
                                .projectId(projectA)
                                .queryText("kiến trúc hybrid search")
                                .maxCandidates(10)
                                .build());

        assertEquals(RetrievalStatus.COMPLETE, result.getRetrievalStatus());
        assertEquals(1, result.getItems().size());
        assertEquals(activeA.getChunkIds().getFirst(), result.getItems().getFirst().getChunkId());
    }

    @Test
    void rrfAndExactLookupRankSpecificCodeAndTableChunkAtTop1() {
        List<Double> queryVec = basisVector(1);
        List<Double> otherVec = basisVector(2);
        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenReturn(
                        EmbeddingBatchResponse.builder()
                                .dispatched(true)
                                .vectors(List.of(queryVec))
                                .build());

        // Distractor chunk with same vector direction but without CF-DEC-038 / embedding_outbox
        ingestAndEmbed(
                projectA,
                "Thảo luận chung về quy trình triển khai hệ thống AI và cấu hình dịch vụ.",
                queryVec);

        // Target chunk containing exact decision code & table name
        KnowledgeIngestionResult target =
                ingestAndEmbed(
                        projectA,
                        "Quyết định CF-DEC-038 quy định bảng embedding_outbox lưu trạng thái đồng bộ vector.",
                        otherVec);

        KnowledgeRetrievalResult result =
                knowledgeRetrievalPort.retrieve(
                        KnowledgeRetrievalRequest.builder()
                                .projectId(projectA)
                                .queryText("Giải thích CF-DEC-038 và bảng embedding_outbox")
                                .maxCandidates(10)
                                .build());

        assertEquals(RetrievalStatus.COMPLETE, result.getRetrievalStatus());
        assertEquals(EvidenceSufficiency.SUFFICIENT, result.getEvidenceSufficiency());
        assertFalse(result.getItems().isEmpty());
        assertEquals(target.getChunkIds().getFirst(), result.getItems().getFirst().getChunkId());
        assertTrue(
                result.getItems()
                        .getFirst()
                        .getMatchedBranches()
                        .contains(HybridRetrievalRepository.BRANCH_EXACT));
    }

    @Test
    void degradesGracefullyToKeywordSearchWhenQueryEmbeddingFails() {
        ingestAndEmbed(
                projectA,
                "Hướng dẫn cấu hình tìm kiếm tiếng Việt không dấu với pgvector và unaccent.",
                basisVector(3));

        when(embeddingClient.embedBatch(any(EmbeddingBatchRequest.class), anyString()))
                .thenThrow(
                        new EmbeddingClientException(
                                ProviderErrorCategory.TRANSIENT_RETRYABLE,
                                "PROVIDER_UNAVAILABLE",
                                true,
                                null,
                                false,
                                "Simulated /embeddings timeout"));

        KnowledgeRetrievalResult result =
                knowledgeRetrievalPort.retrieve(
                        KnowledgeRetrievalRequest.builder()
                                .projectId(projectA)
                                .queryText("tim kiem tieng viet unaccent")
                                .maxCandidates(10)
                                .build());

        assertEquals(RetrievalStatus.PARTIAL, result.getRetrievalStatus());
        assertFalse(result.getItems().isEmpty(), "Keyword FTS fallback must still return hits");
        assertEquals(1, result.getDegradedComponents().size());
        assertEquals(
                DegradedComponentType.VECTOR_SEARCH,
                result.getDegradedComponents().getFirst().getComponent());
        assertEquals(
                DegradedComponentStatus.UNAVAILABLE,
                result.getDegradedComponents().getFirst().getStatus());
        assertEquals(
                "PROVIDER_UNAVAILABLE", result.getDegradedComponents().getFirst().getReasonCode());
    }

    private KnowledgeIngestionResult ingestAndEmbed(
            UUID targetProjectId, String text, List<Double> vector) {
        KnowledgeIngestionResult ingested =
                ingestionService.ingestCommittedMainTurn(
                        TurnIngestionRequest.builder()
                                .projectId(targetProjectId)
                                .conversationId(UUID.randomUUID())
                                .mainStageId(UUID.randomUUID())
                                .turnId(UUID.randomUUID())
                                .userContentBlocks(List.of(TurnContentBlockDto.textBlock(text)))
                                .assistantText("Xác nhận: " + text)
                                .build());
        String vecLit = KnowledgeIngestionRepository.toPgVectorLiteral(vector);
        for (UUID chunkId : ingested.getChunkIds()) {
            jdbcTemplate.update(
                    "UPDATE contextflow.knowledge_chunks SET embedding = ?::public.vector WHERE id = ?",
                    vecLit,
                    chunkId);
        }
        return ingested;
    }

    private static List<Double> basisVector(int hotIndex) {
        List<Double> vec = new ArrayList<>(1536);
        for (int i = 0; i < 1536; i++) {
            vec.add(i == hotIndex ? 1.0 : 0.0);
        }
        return vec;
    }
}

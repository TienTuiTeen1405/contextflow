package com.contextflow.retrieval.service.impl;

import com.contextflow.context.dto.DegradedComponentDto;
import com.contextflow.context.dto.DegradedComponentStatus;
import com.contextflow.context.dto.DegradedComponentType;
import com.contextflow.context.dto.EvidenceSufficiency;
import com.contextflow.context.dto.IntentContextDto;
import com.contextflow.context.dto.KnowledgeRetrievalRequest;
import com.contextflow.context.dto.KnowledgeRetrievalResult;
import com.contextflow.context.dto.RetrievalStatus;
import com.contextflow.context.port.KnowledgeRetrievalPort;
import com.contextflow.knowledge.client.EmbeddingClientException;
import com.contextflow.knowledge.dto.ActiveIndexGenerationDto;
import com.contextflow.knowledge.dto.EmbeddingBatchRequest;
import com.contextflow.knowledge.dto.EmbeddingBatchResponse;
import com.contextflow.knowledge.port.EmbeddingClient;
import com.contextflow.knowledge.port.EmbeddingCredentialProvider;
import com.contextflow.knowledge.repository.KnowledgeIngestionRepository;
import com.contextflow.retrieval.dto.RetrievedChunkHit;
import com.contextflow.retrieval.intent.IntentResolver;
import com.contextflow.retrieval.ranking.RrfFusionRanker;
import com.contextflow.retrieval.repository.HybridRetrievalRepository;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Hybrid RAG implementation of {@link KnowledgeRetrievalPort} running Vector Search (pgvector HNSW
 * relaxed_order), Vietnamese FTS (public.cf_vi), and Exact Technical Terms Lookup in parallel with
 * RRF (k=60), RelevanceGate, and graceful RetrievalDegraded fallback.
 */
@Slf4j
@Service
public class HybridKnowledgeRetrievalService implements KnowledgeRetrievalPort {
    private static final String TASK_TYPE_QUERY_EMBEDDING = "QUERY_EMBEDDING";
    private static final int BRANCH_CANDIDATE_MULTIPLIER = 2;

    private final IntentResolver intentResolver;
    private final KnowledgeIngestionRepository ingestionRepository;
    private final HybridRetrievalRepository retrievalRepository;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingCredentialProvider credentialProvider;
    private final RrfFusionRanker rrfFusionRanker;
    private final ExecutorService retrievalExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public HybridKnowledgeRetrievalService(
            IntentResolver intentResolver,
            KnowledgeIngestionRepository ingestionRepository,
            HybridRetrievalRepository retrievalRepository,
            EmbeddingClient embeddingClient,
            EmbeddingCredentialProvider credentialProvider,
            RrfFusionRanker rrfFusionRanker) {
        this.intentResolver = intentResolver;
        this.ingestionRepository = ingestionRepository;
        this.retrievalRepository = retrievalRepository;
        this.embeddingClient = embeddingClient;
        this.credentialProvider = credentialProvider;
        this.rrfFusionRanker = rrfFusionRanker;
    }

    @PreDestroy
    public void shutdownExecutor() {
        retrievalExecutor.close();
    }

    @Override
    public KnowledgeRetrievalResult retrieve(KnowledgeRetrievalRequest request) {
        if (request == null
                || request.getProjectId() == null
                || request.getQueryText() == null
                || request.getQueryText().isBlank()) {
            return KnowledgeRetrievalResult.empty();
        }

        Optional<ActiveIndexGenerationDto> activeGenOpt =
                ingestionRepository.findActiveIndexGeneration(request.getProjectId());
        if (activeGenOpt.isEmpty()) {
            return KnowledgeRetrievalResult.unavailable("ACTIVE_INDEX_GENERATION_MISSING");
        }
        ActiveIndexGenerationDto activeGen = activeGenOpt.get();

        IntentContextDto intent =
                intentResolver.resolve(request.getQueryText(), request.getIntentContext());
        int maxCandidates = Math.max(1, request.getMaxCandidates());
        int perBranchLimit = Math.max(10, maxCandidates * BRANCH_CANDIDATE_MULTIPLIER);

        CompletableFuture<BranchOutcome> vectorFuture =
                CompletableFuture.supplyAsync(
                        () -> runVectorBranch(request, activeGen, perBranchLimit),
                        retrievalExecutor);

        CompletableFuture<BranchOutcome> keywordFuture =
                CompletableFuture.supplyAsync(
                        () -> runKeywordBranch(request, activeGen, perBranchLimit),
                        retrievalExecutor);

        CompletableFuture<List<RetrievedChunkHit>> exactFuture =
                intent.isExactLookup() && !intent.getResolvedEntities().isEmpty()
                        ? CompletableFuture.supplyAsync(
                                () ->
                                        runExactBranch(
                                                request,
                                                activeGen,
                                                intent.getResolvedEntities(),
                                                perBranchLimit),
                                retrievalExecutor)
                        : CompletableFuture.completedFuture(List.of());

        BranchOutcome vectorOutcome = vectorFuture.join();
        BranchOutcome keywordOutcome = keywordFuture.join();
        List<RetrievedChunkHit> exactHits = exactFuture.join();

        List<DegradedComponentDto> degraded = new ArrayList<>();
        if (vectorOutcome.degradedComponent != null) {
            degraded.add(vectorOutcome.degradedComponent);
        }
        if (keywordOutcome.degradedComponent != null) {
            degraded.add(keywordOutcome.degradedComponent);
        }

        RetrievalStatus retrievalStatus;
        if (degraded.isEmpty()) {
            retrievalStatus = RetrievalStatus.COMPLETE;
        } else if (vectorOutcome.degradedComponent != null
                && keywordOutcome.degradedComponent != null
                && exactHits.isEmpty()) {
            retrievalStatus = RetrievalStatus.UNAVAILABLE;
        } else {
            retrievalStatus = RetrievalStatus.PARTIAL;
        }

        RrfFusionRanker.RankedRetrievalOutcome ranked =
                rrfFusionRanker.fuseAndRank(
                        vectorOutcome.hits, keywordOutcome.hits, exactHits, maxCandidates);

        return KnowledgeRetrievalResult.builder()
                .items(ranked.getCandidates())
                .retrievalStatus(retrievalStatus)
                .evidenceSufficiency(
                        retrievalStatus == RetrievalStatus.UNAVAILABLE
                                ? EvidenceSufficiency.INSUFFICIENT
                                : ranked.getEvidenceSufficiency())
                .degradedComponents(degraded)
                .build();
    }

    private BranchOutcome runVectorBranch(
            KnowledgeRetrievalRequest request, ActiveIndexGenerationDto activeGen, int limit) {
        try {
            Optional<String> credOpt =
                    credentialProvider.resolvePlatformCredential(request.getProjectId());
            if (credOpt.isEmpty() || credOpt.get().isBlank()) {
                return BranchOutcome.degraded(
                        DegradedComponentType.VECTOR_SEARCH, "QUERY_EMBEDDING_CREDENTIAL_MISSING");
            }

            EmbeddingBatchRequest embReq =
                    EmbeddingBatchRequest.builder()
                            .correlationId(UUID.randomUUID())
                            .providerCallId(UUID.randomUUID())
                            .taskType(TASK_TYPE_QUERY_EMBEDDING)
                            .model(activeGen.getEmbeddingModel())
                            .outputDimensionality(activeGen.getOutputDimensionality())
                            .texts(List.of(request.getQueryText()))
                            .build();

            EmbeddingBatchResponse embRes = embeddingClient.embedBatch(embReq, credOpt.get());
            if (embRes == null || embRes.getVectors() == null || embRes.getVectors().isEmpty()) {
                return BranchOutcome.degraded(
                        DegradedComponentType.VECTOR_SEARCH, "QUERY_EMBEDDING_EMPTY");
            }
            List<RetrievedChunkHit> hits =
                    retrievalRepository.searchVector(
                            request.getProjectId(),
                            activeGen.getId(),
                            embRes.getVectors().getFirst(),
                            limit);
            return BranchOutcome.ok(hits);
        } catch (EmbeddingClientException ex) {
            log.warn(
                    "Vector search degraded due to embedding error category={} code={}",
                    ex.getErrorCategory(),
                    ex.getErrorCode());
            return BranchOutcome.degraded(
                    DegradedComponentType.VECTOR_SEARCH,
                    ex.getErrorCode() != null ? ex.getErrorCode() : "QUERY_EMBEDDING_UNAVAILABLE");
        } catch (Exception ex) {
            log.warn("Vector search degraded exc_type={}", ex.getClass().getSimpleName());
            return BranchOutcome.degraded(
                    DegradedComponentType.VECTOR_SEARCH, "VECTOR_SEARCH_UNAVAILABLE");
        }
    }

    private BranchOutcome runKeywordBranch(
            KnowledgeRetrievalRequest request, ActiveIndexGenerationDto activeGen, int limit) {
        try {
            List<RetrievedChunkHit> hits =
                    retrievalRepository.searchKeyword(
                            request.getProjectId(),
                            activeGen.getId(),
                            request.getQueryText(),
                            limit);
            return BranchOutcome.ok(hits);
        } catch (Exception ex) {
            log.warn("Keyword search degraded exc_type={}", ex.getClass().getSimpleName());
            return BranchOutcome.degraded(
                    DegradedComponentType.KEYWORD_SEARCH, "KEYWORD_SEARCH_UNAVAILABLE");
        }
    }

    private List<RetrievedChunkHit> runExactBranch(
            KnowledgeRetrievalRequest request,
            ActiveIndexGenerationDto activeGen,
            List<String> terms,
            int limit) {
        try {
            return retrievalRepository.searchExactTerms(
                    request.getProjectId(), activeGen.getId(), terms, limit);
        } catch (Exception ex) {
            log.warn("Exact lookup branch failed exc_type={}", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private static final class BranchOutcome {
        private final List<RetrievedChunkHit> hits;
        private final DegradedComponentDto degradedComponent;

        private BranchOutcome(
                List<RetrievedChunkHit> hits, DegradedComponentDto degradedComponent) {
            this.hits = hits;
            this.degradedComponent = degradedComponent;
        }

        private static BranchOutcome ok(List<RetrievedChunkHit> hits) {
            return new BranchOutcome(hits, null);
        }

        private static BranchOutcome degraded(DegradedComponentType type, String reasonCode) {
            return new BranchOutcome(
                    List.of(),
                    DegradedComponentDto.builder()
                            .component(type)
                            .status(DegradedComponentStatus.UNAVAILABLE)
                            .reasonCode(reasonCode)
                            .build());
        }
    }
}

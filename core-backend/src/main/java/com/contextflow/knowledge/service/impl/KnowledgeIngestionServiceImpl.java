package com.contextflow.knowledge.service.impl;

import com.contextflow.common.api.ApiProblem;
import com.contextflow.knowledge.chunking.KnowledgeChunker;
import com.contextflow.knowledge.chunking.PreChunkingFilter;
import com.contextflow.knowledge.dto.ActiveIndexGenerationDto;
import com.contextflow.knowledge.dto.KnowledgeChunkDraft;
import com.contextflow.knowledge.dto.KnowledgeIngestionResult;
import com.contextflow.knowledge.dto.KnowledgeItemType;
import com.contextflow.knowledge.dto.TurnIngestionRequest;
import com.contextflow.knowledge.repository.KnowledgeIngestionRepository;
import com.contextflow.knowledge.service.KnowledgeIngestionService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically projects committed MAIN-route turns into knowledge_items, chunked knowledge_chunks
 * (with Pre-Chunking Exclusion for exclude_from_auto_kb quotes), and PENDING embedding_outbox rows.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionServiceImpl implements KnowledgeIngestionService {
    private final PreChunkingFilter preChunkingFilter;
    private final KnowledgeChunker knowledgeChunker;
    private final KnowledgeIngestionRepository repository;

    @Override
    @Transactional
    public KnowledgeIngestionResult ingestCommittedMainTurn(TurnIngestionRequest request) {
        if (request == null || request.getProjectId() == null || request.getTurnId() == null) {
            throw ApiProblem.invalid("projectId and turnId are required for KB ingestion.");
        }
        // Only MAIN-route turns are auto-projected to Project KB.
        if (request.getSubBranchId() != null) {
            return KnowledgeIngestionResult.builder().skipped(true).build();
        }

        // Idempotent guard against duplicate projection for the same committed MAIN turn.
        var existingItemId =
                repository.findMainTurnKnowledgeItemId(request.getProjectId(), request.getTurnId());
        if (existingItemId.isPresent()) {
            return KnowledgeIngestionResult.builder()
                    .knowledgeItemId(existingItemId.get())
                    .skipped(true)
                    .build();
        }

        PreChunkingFilter.FilteredTurnContent filtered = preChunkingFilter.filter(request);
        if (filtered.getProjectedContent().isBlank()) {
            return KnowledgeIngestionResult.builder()
                    .excludedQuoteCount(filtered.getExcludedQuoteCount())
                    .skipped(true)
                    .build();
        }

        List<KnowledgeChunkDraft> drafts = knowledgeChunker.chunk(filtered.getProjectedContent());
        if (drafts.isEmpty()) {
            return KnowledgeIngestionResult.builder()
                    .excludedQuoteCount(filtered.getExcludedQuoteCount())
                    .skipped(true)
                    .build();
        }

        ActiveIndexGenerationDto activeGen =
                repository
                        .findActiveIndexGeneration(request.getProjectId())
                        .orElseThrow(() ->
                                ApiProblem.invalid(
                                        "No ACTIVE index generation found for project."));

        long revision = Math.max(1L, request.getSourceRevision());
        UUID itemId =
                repository.insertKnowledgeItem(
                        request.getProjectId(),
                        KnowledgeItemType.MAIN_TURN,
                        request.getConversationId(),
                        request.getMainStageId(),
                        request.getTurnId(),
                        null,
                        revision,
                        request.getTitle(),
                        filtered.getProjectedContent());

        List<UUID> chunkIds = new ArrayList<>(drafts.size());
        List<UUID> outboxIds = new ArrayList<>(drafts.size());
        for (KnowledgeChunkDraft draft : drafts) {
            UUID chunkId =
                    repository.insertKnowledgeChunk(
                            request.getProjectId(),
                            itemId,
                            activeGen.getId(),
                            request.getMainStageId(),
                            KnowledgeItemType.MAIN_TURN.name(),
                            revision,
                            draft);
            chunkIds.add(chunkId);

            String contentHash = KnowledgeIngestionRepository.sha256Hex(draft.getContent());
            UUID outboxId =
                    repository.insertOutboxItem(
                            request.getProjectId(),
                            itemId,
                            chunkId,
                            activeGen.getId(),
                            revision,
                            contentHash,
                            draft.getEstimatedTokens());
            outboxIds.add(outboxId);
        }

        return KnowledgeIngestionResult.builder()
                .knowledgeItemId(itemId)
                .indexGenerationId(activeGen.getId())
                .chunkIds(chunkIds)
                .outboxIds(outboxIds)
                .excludedQuoteCount(filtered.getExcludedQuoteCount())
                .skipped(false)
                .build();
    }
}

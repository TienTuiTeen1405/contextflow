package com.contextflow.retrieval.ranking;

import com.contextflow.context.dto.EvidenceSufficiency;
import com.contextflow.context.dto.KnowledgeCandidateDto;
import com.contextflow.retrieval.dto.RetrievedChunkHit;
import com.contextflow.retrieval.repository.HybridRetrievalRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reciprocal Rank Fusion (k = 60), FinalRanker (exact-lookup + authority + freshness), and
 * RelevanceGate (noise filtering + EvidenceSufficiency classification).
 */
@Component
public class RrfFusionRanker {
    public static final int RRF_K = 60;
    public static final double RELEVANCE_GATE_THRESHOLD = 0.008;
    private static final double EXACT_LOOKUP_BONUS = 0.05;
    private static final double SUFFICIENT_SCORE_THRESHOLD = 0.025;

    public RankedRetrievalOutcome fuseAndRank(
            List<RetrievedChunkHit> vectorHits,
            List<RetrievedChunkHit> keywordHits,
            List<RetrievedChunkHit> exactHits,
            int maxCandidates) {
        Map<UUID, CandidateAccumulator> accByChunk = new LinkedHashMap<>();

        accumulate(accByChunk, vectorHits, HybridRetrievalRepository.BRANCH_VECTOR);
        accumulate(accByChunk, keywordHits, HybridRetrievalRepository.BRANCH_KEYWORD);
        accumulate(accByChunk, exactHits, HybridRetrievalRepository.BRANCH_EXACT);

        List<KnowledgeCandidateDto> scored = new ArrayList<>(accByChunk.size());
        for (CandidateAccumulator acc : accByChunk.values()) {
            double finalScore = computeFinalScore(acc);
            if (!passesRelevanceGate(acc, finalScore)) {
                continue;
            }
            RetrievedChunkHit rep = acc.representative;
            scored.add(
                    KnowledgeCandidateDto.builder()
                            .knowledgeItemId(rep.getKnowledgeItemId())
                            .chunkId(rep.getChunkId())
                            .sourceTurnId(rep.getSourceTurnId())
                            .promotedFromQuoteRefId(rep.getPromotedFromQuoteRefId())
                            .itemType(rep.getItemType())
                            .sourceType(rep.getSourceType())
                            .title(rep.getTitle())
                            .content(rep.getContent())
                            .sourceRevision(
                                    rep.getSourceRevision() != null ? rep.getSourceRevision() : "1")
                            .linkStatus("AVAILABLE")
                            .relevanceScore(finalScore)
                            .createdAt(rep.getCreatedAt())
                            .matchedBranches(new ArrayList<>(acc.matchedBranches))
                            .build());
        }

        scored.sort(
                Comparator.comparingDouble(KnowledgeCandidateDto::getRelevanceScore)
                        .reversed()
                        .thenComparing(
                                Comparator.comparing(
                                                KnowledgeCandidateDto::getCreatedAt,
                                                Comparator.nullsLast(Comparator.naturalOrder()))
                                        .reversed())
                        .thenComparing(
                                KnowledgeCandidateDto::getChunkId,
                                Comparator.nullsLast(Comparator.naturalOrder())));

        int limit = Math.max(1, maxCandidates);
        List<KnowledgeCandidateDto> capped =
                scored.size() <= limit ? scored : new ArrayList<>(scored.subList(0, limit));

        EvidenceSufficiency sufficiency = classifySufficiency(capped);
        return RankedRetrievalOutcome.builder()
                .candidates(capped)
                .evidenceSufficiency(sufficiency)
                .build();
    }

    private static void accumulate(
            Map<UUID, CandidateAccumulator> accByChunk,
            List<RetrievedChunkHit> hits,
            String branch) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            RetrievedChunkHit hit = hits.get(i);
            if (hit == null || hit.getChunkId() == null) {
                continue;
            }
            int rank = i + 1;
            double rrfContribution = 1.0 / (RRF_K + rank);
            CandidateAccumulator acc =
                    accByChunk.computeIfAbsent(
                            hit.getChunkId(), id -> new CandidateAccumulator(hit));
            acc.rrfScore += rrfContribution;
            acc.matchedBranches.add(branch);
            if (HybridRetrievalRepository.BRANCH_EXACT.equals(branch)) {
                acc.exactMatched = true;
            }
        }
    }

    private static double computeFinalScore(CandidateAccumulator acc) {
        double score = acc.rrfScore;
        if (acc.exactMatched) {
            score += EXACT_LOOKUP_BONUS;
        }
        score *= authorityMultiplier(acc.representative.getItemType());
        return score;
    }

    private static double authorityMultiplier(String itemType) {
        if ("PROMOTED_QUOTE".equals(itemType) || "CONDENSED_CONTEXT_NOTE".equals(itemType)) {
            return 1.10;
        }
        if ("SUB_BRANCH_SELECTION".equals(itemType)) {
            return 1.05;
        }
        return 1.0;
    }

    private static boolean passesRelevanceGate(CandidateAccumulator acc, double finalScore) {
        if (acc.exactMatched) {
            return true;
        }
        return finalScore >= RELEVANCE_GATE_THRESHOLD;
    }

    private static EvidenceSufficiency classifySufficiency(List<KnowledgeCandidateDto> candidates) {
        if (candidates.isEmpty()) {
            return EvidenceSufficiency.INSUFFICIENT;
        }
        KnowledgeCandidateDto top = candidates.getFirst();
        if (top.getRelevanceScore() >= SUFFICIENT_SCORE_THRESHOLD
                || top.getMatchedBranches().size() >= 2
                || top.getMatchedBranches().contains(HybridRetrievalRepository.BRANCH_EXACT)) {
            return EvidenceSufficiency.SUFFICIENT;
        }
        return EvidenceSufficiency.PARTIAL;
    }

    private static final class CandidateAccumulator {
        private final RetrievedChunkHit representative;
        private final Set<String> matchedBranches = new LinkedHashSet<>();
        private double rrfScore;
        private boolean exactMatched;

        private CandidateAccumulator(RetrievedChunkHit representative) {
            this.representative = representative;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankedRetrievalOutcome {
        @Builder.Default private List<KnowledgeCandidateDto> candidates = new ArrayList<>();

        @Builder.Default
        private EvidenceSufficiency evidenceSufficiency = EvidenceSufficiency.INSUFFICIENT;
    }
}

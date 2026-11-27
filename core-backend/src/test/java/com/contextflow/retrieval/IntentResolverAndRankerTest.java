package com.contextflow.retrieval;

import static org.junit.jupiter.api.Assertions.*;

import com.contextflow.context.dto.EvidenceSufficiency;
import com.contextflow.context.dto.IntentContextDto;
import com.contextflow.retrieval.dto.RetrievedChunkHit;
import com.contextflow.retrieval.intent.IntentResolver;
import com.contextflow.retrieval.ranking.RrfFusionRanker;
import com.contextflow.retrieval.repository.HybridRetrievalRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntentResolverAndRankerTest {
    private final IntentResolver intentResolver = new IntentResolver();
    private final RrfFusionRanker ranker = new RrfFusionRanker();

    @Test
    void intentResolverDetectsCompareHistoricalAndExactTechnicalTerms() {
        IntentContextDto intent =
                intentResolver.resolve(
                        "So sánh CF-DEC-038 trước đây với bảng embedding_outbox hiện tại", null);

        assertEquals("COMPARE", intent.getPrimaryIntent());
        assertEquals("HISTORICAL_OR_COMPARE", intent.getTimeScope());
        assertTrue(intent.isExactLookup());
        assertTrue(intent.getResolvedEntities().contains("CF-DEC-038"));
        assertTrue(intent.getResolvedEntities().contains("embedding_outbox"));
    }

    @Test
    void rrfFusionRankerPromotesExactLookupAndMultiBranchConsensusToTop1() {
        UUID exactChunkId = UUID.randomUUID();
        UUID vectorOnlyChunkId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-28T00:00:00Z");

        RetrievedChunkHit vectorTop =
                RetrievedChunkHit.builder()
                        .chunkId(vectorOnlyChunkId)
                        .knowledgeItemId(UUID.randomUUID())
                        .itemType("MAIN_TURN")
                        .sourceType("MAIN_TURN")
                        .content("General discussion about embeddings")
                        .createdAt(now)
                        .rawScore(0.91)
                        .branchName(HybridRetrievalRepository.BRANCH_VECTOR)
                        .build();

        RetrievedChunkHit exactHit =
                RetrievedChunkHit.builder()
                        .chunkId(exactChunkId)
                        .knowledgeItemId(UUID.randomUUID())
                        .itemType("MAIN_TURN")
                        .sourceType("MAIN_TURN")
                        .content("Specific CF-DEC-038 table embedding_outbox specification")
                        .createdAt(now)
                        .rawScore(2.0)
                        .branchName(HybridRetrievalRepository.BRANCH_EXACT)
                        .build();

        RrfFusionRanker.RankedRetrievalOutcome outcome =
                ranker.fuseAndRank(
                        List.of(vectorTop, exactHit), List.of(exactHit), List.of(exactHit), 10);

        assertEquals(2, outcome.getCandidates().size());
        assertEquals(exactChunkId, outcome.getCandidates().getFirst().getChunkId());
        assertEquals(EvidenceSufficiency.SUFFICIENT, outcome.getEvidenceSufficiency());
    }
}

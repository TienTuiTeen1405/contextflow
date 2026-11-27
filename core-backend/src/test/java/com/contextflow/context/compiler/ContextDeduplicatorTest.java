package com.contextflow.context.compiler;

import static org.junit.jupiter.api.Assertions.*;

import com.contextflow.context.dto.BudgetExplicitRefDto;
import com.contextflow.context.dto.BudgetHistoryItemDto;
import com.contextflow.context.dto.BudgetKnowledgeItemDto;
import com.contextflow.context.dto.ContextSourceItemDto;
import com.contextflow.context.dto.ContinuityTurnDto;
import com.contextflow.context.dto.ExplicitReferenceItemDto;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextDeduplicatorTest {
    private final ContextDeduplicator deduplicator = new ContextDeduplicator();

    @Test
    void enforcesFourTierPrecedenceAndKeepsFresherItem() {
        UUID turnInMainRef = UUID.randomUUID();
        UUID turnInActive = UUID.randomUUID();
        UUID turnInHistoryOnly = UUID.randomUUID();
        UUID kbIndependentNoteId = UUID.randomUUID();
        UUID kbUniqueId = UUID.randomUUID();

        BudgetExplicitRefDto mainRef =
                BudgetExplicitRefDto.builder()
                        .targetStageId(UUID.randomUUID())
                        .targetTurnId(turnInMainRef)
                        .explicitReference(
                                ExplicitReferenceItemDto.builder()
                                        .kind("MAIN_REF")
                                        .id("ref_1")
                                        .sourceType("TURN")
                                        .snapshotText("Explicit turn snapshot")
                                        .sourceRevision("1")
                                        .authority("EXPLICIT_REFERENCE")
                                        .build())
                        .build();

        BudgetExplicitRefDto quoteOverlappingActive =
                BudgetExplicitRefDto.builder()
                        .sourceId(turnInActive)
                        .explicitReference(
                                ExplicitReferenceItemDto.builder()
                                        .kind("QUOTE")
                                        .id("q_1")
                                        .sourceType("MAIN_TURN")
                                        .snapshotText("short excerpt")
                                        .sourceRevision("1")
                                        .authority("EXPLICIT_REFERENCE")
                                        .build())
                        .build();

        ContinuityTurnDto activeTurn =
                ContinuityTurnDto.builder()
                        .turnRef("turn_1")
                        .userTextBlocks(List.of("Full active turn containing short excerpt"))
                        .build();

        // History duplicates: turnInMainRef (dropped by Tier 1), turnInActive (dropped by Tier 2),
        // turnInHistoryOnly rev 1 & rev 2 (retains fresher rev 2).
        BudgetHistoryItemDto histFromMainRef = historyItem(turnInMainRef, 1, 1L, 1L, "Turn 1");
        BudgetHistoryItemDto histFromActive = historyItem(turnInActive, 1, 2L, 1L, "Turn 2");
        BudgetHistoryItemDto histStale = historyItem(turnInHistoryOnly, 1, 3L, 1L, "Old rev");
        BudgetHistoryItemDto histFresher = historyItem(turnInHistoryOnly, 1, 3L, 2L, "Fresh rev");

        // KB items:
        // - MAIN_TURN projection for turnInActive -> dropped (Tier 2 wins)
        // - MAIN_TURN projection for turnInHistoryOnly -> dropped (Tier 3 wins)
        // - Identical normalized text to quoteOverlappingActive -> dropped (Tier 1 wins)
        // - Independent CONDENSED_CONTEXT_NOTE pointing to turnInHistoryOnly -> RETAINED
        // (CF-DEC-031)
        // - Duplicate chunk rev 1 vs rev 3 -> retains fresher rev 3
        BudgetKnowledgeItemDto kbDupActive =
                kbItem(
                        UUID.randomUUID(),
                        null,
                        turnInActive,
                        "MAIN_TURN",
                        "MAIN_TURN",
                        "1",
                        "Active dup",
                        0.9);
        BudgetKnowledgeItemDto kbDupHistory =
                kbItem(
                        UUID.randomUUID(),
                        null,
                        turnInHistoryOnly,
                        "MAIN_TURN",
                        "MAIN_TURN",
                        "1",
                        "Hist dup",
                        0.9);
        BudgetKnowledgeItemDto kbDupQuoteText =
                kbItem(
                        UUID.randomUUID(),
                        null,
                        null,
                        "PROMOTED_QUOTE",
                        "KB_CHUNK",
                        "1",
                        "  Short   Excerpt ",
                        0.95);
        BudgetKnowledgeItemDto kbIndependent =
                kbItem(
                        kbIndependentNoteId,
                        null,
                        turnInHistoryOnly,
                        "CONDENSED_CONTEXT_NOTE",
                        "CONDENSED_CONTEXT_NOTE",
                        "1",
                        "Synthesized architecture decision note",
                        0.85);
        UUID sharedChunkId = UUID.randomUUID();
        BudgetKnowledgeItemDto kbChunkOld =
                kbItem(
                        kbUniqueId,
                        sharedChunkId,
                        null,
                        "CONDENSED_CONTEXT_NOTE",
                        "KB_CHUNK",
                        "1",
                        "Unique chunk v1",
                        0.7);
        BudgetKnowledgeItemDto kbChunkFresh =
                kbItem(
                        kbUniqueId,
                        sharedChunkId,
                        null,
                        "CONDENSED_CONTEXT_NOTE",
                        "KB_CHUNK",
                        "3",
                        "Unique chunk v3",
                        0.8);

        DeduplicationOutcome outcome =
                deduplicator.deduplicate(
                        List.of(mainRef, quoteOverlappingActive),
                        List.of(activeTurn),
                        Set.of(turnInActive),
                        List.of(histFromMainRef, histFromActive, histStale, histFresher),
                        List.of(
                                kbDupActive,
                                kbDupHistory,
                                kbDupQuoteText,
                                kbIndependent,
                                kbChunkOld,
                                kbChunkFresh));

        assertEquals(2, outcome.getExplicitReferences().size());
        assertEquals(1, outcome.getHistoryItems().size());
        assertEquals(
                "Fresh rev", outcome.getHistoryItems().getFirst().getSourceItem().getContent());
        assertEquals(2, outcome.getKnowledgeItems().size());
        assertEquals(
                List.of("Synthesized architecture decision note", "Unique chunk v3"),
                outcome.getKnowledgeItems().stream()
                        .map(item -> item.getSourceItem().getContent())
                        .toList());
        assertEquals(3, outcome.getDroppedHistoryDuplicates());
        assertEquals(4, outcome.getDroppedKnowledgeDuplicates());
    }

    @Test
    void recordsAliasMappingWhenDeduplicatingIdenticalQuotes() {
        UUID sourceTurnId = UUID.randomUUID();
        BudgetExplicitRefDto q1 =
                BudgetExplicitRefDto.builder()
                        .sourceId(sourceTurnId)
                        .explicitReference(
                                ExplicitReferenceItemDto.builder()
                                        .kind("QUOTE")
                                        .id("q1")
                                        .sourceType("MAIN_TURN")
                                        .snapshotText("Shared excerpt")
                                        .sourceRevision("1")
                                        .authority("EXPLICIT_REFERENCE")
                                        .build())
                        .build();
        BudgetExplicitRefDto q2 =
                BudgetExplicitRefDto.builder()
                        .sourceId(sourceTurnId)
                        .explicitReference(
                                ExplicitReferenceItemDto.builder()
                                        .kind("QUOTE")
                                        .id("q2")
                                        .sourceType("MAIN_TURN")
                                        .snapshotText("  shared   excerpt  ")
                                        .sourceRevision("1")
                                        .authority("EXPLICIT_REFERENCE")
                                        .build())
                        .build();

        DeduplicationOutcome outcome =
                deduplicator.deduplicate(
                        List.of(q1, q2), List.of(), Set.of(), List.of(), List.of());

        assertEquals(1, outcome.getExplicitReferences().size());
        assertEquals(
                "q1", outcome.getExplicitReferences().getFirst().getExplicitReference().getId());
        assertEquals(1, outcome.getDroppedExplicitDuplicates());
        assertEquals("q1", outcome.getExplicitReferenceAliases().get("q2"));
    }

    private static BudgetHistoryItemDto historyItem(
            UUID turnId, int stageOrder, long sequenceNo, long rev, String content) {
        return BudgetHistoryItemDto.builder()
                .turnId(turnId)
                .stageOrder(stageOrder)
                .sequenceNo(sequenceNo)
                .sourceRevision(rev)
                .createdAt(Instant.parse("2026-11-26T00:00:00Z"))
                .sourceItem(
                        ContextSourceItemDto.builder()
                                .sourceRef("hist_" + sequenceNo)
                                .sourceType("MAIN_TURN")
                                .content(content)
                                .authority("CONVERSATION_HISTORY")
                                .sourceRevision(String.valueOf(rev))
                                .sourceLinkStatus("AVAILABLE")
                                .build())
                .build();
    }

    private static BudgetKnowledgeItemDto kbItem(
            UUID kbId,
            UUID chunkId,
            UUID sourceTurnId,
            String itemType,
            String sourceType,
            String rev,
            String content,
            double score) {
        return BudgetKnowledgeItemDto.builder()
                .knowledgeItemId(kbId)
                .chunkId(chunkId)
                .sourceTurnId(sourceTurnId)
                .itemType(itemType)
                .relevanceScore(score)
                .createdAt(Instant.parse("2026-11-26T00:00:00Z"))
                .sourceItem(
                        ContextSourceItemDto.builder()
                                .sourceRef("kb_" + kbId.toString().substring(0, 4))
                                .sourceType(sourceType)
                                .content(content)
                                .authority("PROJECT_KB")
                                .sourceRevision(rev)
                                .sourceLinkStatus("AVAILABLE")
                                .build())
                .build();
    }
}

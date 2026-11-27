package com.contextflow.context.budget;

import static org.junit.jupiter.api.Assertions.*;

import com.contextflow.context.dto.BudgetHistoryItemDto;
import com.contextflow.context.dto.BudgetKnowledgeItemDto;
import com.contextflow.context.dto.ContentBlockDto;
import com.contextflow.context.dto.ContextBudgetResultDto;
import com.contextflow.context.dto.ContextSourceItemDto;
import com.contextflow.context.dto.CurrentUserRequestDto;
import com.contextflow.context.dto.ExplicitReferenceItemDto;
import com.contextflow.context.dto.InstructionItemDto;
import com.contextflow.context.dto.IntelligenceBackgroundDto;
import com.contextflow.context.dto.IntelligenceContextDto;
import com.contextflow.context.dto.IntelligenceInstructionsDto;
import com.contextflow.context.dto.KnowledgeAndEvidenceDto;
import com.contextflow.context.dto.PromptEnvelopeDto;
import com.contextflow.context.dto.ResponseInstructionsDto;
import com.contextflow.context.repository.ChapterSummaryRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextBudgetManagerTest {
    private final LocalTokenEstimator estimator = new LocalTokenEstimator(new ObjectMapper());
    private final ContextBudgetManager budgetManager = new ContextBudgetManager(estimator);

    @Test
    void truncatesLowestRelevanceKbThenOldestHistoryAbove32KWhileProtectingInvariants() {
        String kbChunk = "k".repeat(28_000); // ~8,050 tokens with 1.15x
        String historyChunk = "h".repeat(64_000); // ~18,400 tokens each -> 2 = 36,800 > 32K

        PromptEnvelopeDto envelope =
                PromptEnvelopeDto.builder()
                        .platformPolicy("STRICT_PLATFORM_POLICY")
                        .currentUserRequest(
                                CurrentUserRequestDto.builder()
                                        .contentBlocks(
                                                List.of(
                                                        ContentBlockDto.textBlock(
                                                                "CRITICAL_USER_REQUEST")))
                                        .build())
                        .intelligenceContext(
                                IntelligenceContextDto.builder()
                                        .background(IntelligenceBackgroundDto.builder().build())
                                        .instructions(
                                                IntelligenceInstructionsDto.builder()
                                                        .mandatoryRules(
                                                                List.of(
                                                                        InstructionItemDto.builder()
                                                                                .id("mrule_1")
                                                                                .priority(0)
                                                                                .content(
                                                                                        "MANDATORY_INVARIANT_RULE")
                                                                                .build()))
                                                        .build())
                                        .build())
                        .knowledgeAndEvidence(
                                KnowledgeAndEvidenceDto.builder()
                                        .sourceOfTruth(
                                                List.of(
                                                        ContextSourceItemDto.builder()
                                                                .sourceRef("sot_1")
                                                                .sourceType("SOT_RECORD")
                                                                .content("OFFICIAL_SOT_DECISION")
                                                                .authority("SOURCE_OF_TRUTH")
                                                                .sourceRevision("1")
                                                                .sourceLinkStatus("AVAILABLE")
                                                                .build()))
                                        .explicitReferences(
                                                List.of(
                                                        ExplicitReferenceItemDto.builder()
                                                                .kind("QUOTE")
                                                                .id("q_1")
                                                                .sourceType("MAIN_TURN")
                                                                .snapshotText("PROTECTED_QUOTE")
                                                                .sourceRevision("1")
                                                                .authority("EXPLICIT_REFERENCE")
                                                                .build()))
                                        .build())
                        .responseInstructions(new ResponseInstructionsDto())
                        .build();

        BudgetKnowledgeItemDto kbLow = kbItem("kb_low", 0.25, kbChunk);
        BudgetKnowledgeItemDto kbHigh = kbItem("kb_high", 0.95, kbChunk);
        BudgetHistoryItemDto histOld = historyItem("hist_old", 1, 1L, historyChunk, null);
        BudgetHistoryItemDto histRecent = historyItem("hist_recent", 2, 1L, historyChunk, null);

        ContextBudgetResultDto result =
                budgetManager.enforceBudget(
                        envelope,
                        List.of(histOld, histRecent),
                        List.of(kbLow, kbHigh),
                        List.of(),
                        true,
                        ContextBudgetManager.DEFAULT_SOFT_CAP_TOKENS);

        assertTrue(result.getEstimatedTokensBeforeTruncation() > 32_000);
        assertTrue(result.getEstimatedInputTokens() <= 32_000);
        assertTrue(result.isPruningApplied());
        assertTrue(result.isHistoryTruncated());
        assertTrue(result.isRolloverSuggested());
        assertFalse(result.isOverBudget());

        PromptEnvelopeDto trimmed = result.getPromptEnvelope();
        assertEquals("STRICT_PLATFORM_POLICY", trimmed.getPlatformPolicy());
        assertEquals(
                "CRITICAL_USER_REQUEST",
                trimmed.getCurrentUserRequest().getContentBlocks().getFirst().getText());
        assertEquals(
                "PROTECTED_QUOTE",
                trimmed.getKnowledgeAndEvidence()
                        .getExplicitReferences()
                        .getFirst()
                        .getSnapshotText());
        assertEquals(
                "MANDATORY_INVARIANT_RULE",
                trimmed.getIntelligenceContext()
                        .getInstructions()
                        .getMandatoryRules()
                        .getFirst()
                        .getContent());
        assertEquals(
                "OFFICIAL_SOT_DECISION",
                trimmed.getKnowledgeAndEvidence().getSourceOfTruth().getFirst().getContent());

        // Lowest relevance KB dropped first, then oldest history dropped leaving recent history
        assertTrue(trimmed.getKnowledgeAndEvidence().getKnowledgeBase().isEmpty());
        assertEquals(1, trimmed.getKnowledgeAndEvidence().getHistory().size());
        assertEquals(
                "hist_recent",
                trimmed.getKnowledgeAndEvidence().getHistory().getFirst().getSourceRef());
    }

    @Test
    void dropsLowestRelevanceKbBeforeHigherRelevanceKb() {
        String kbChunk = "k".repeat(64_000); // ~18,400 tokens each -> 2 = 36,800 > 32K
        PromptEnvelopeDto envelope = minimalEnvelope();

        BudgetKnowledgeItemDto kbLow = kbItem("kb_low", 0.15, kbChunk);
        BudgetKnowledgeItemDto kbHigh = kbItem("kb_high", 0.90, kbChunk);

        ContextBudgetResultDto result =
                budgetManager.enforceBudget(
                        envelope, List.of(), List.of(kbHigh, kbLow), List.of(), true, 32_000);

        assertTrue(result.isPruningApplied());
        assertFalse(result.isHistoryTruncated());
        assertFalse(result.isRolloverSuggested());
        assertEquals(
                1, result.getPromptEnvelope().getKnowledgeAndEvidence().getKnowledgeBase().size());
        assertEquals(
                "kb_high",
                result.getPromptEnvelope()
                        .getKnowledgeAndEvidence()
                        .getKnowledgeBase()
                        .getFirst()
                        .getSourceRef());
    }

    @Test
    void rolloverSuggestedIsFalseOnSubBranchAndFalseWhenOnlyKbWasEvicted() {
        String largeChunk = "k".repeat(70_000); // ~20,125 tokens each with 1.15x
        PromptEnvelopeDto envelope = minimalEnvelope();

        // Only KB exceeds 32K -> KB evicted, history untouched -> rolloverSuggested must stay false
        BudgetKnowledgeItemDto kb1 = kbItem("kb_1", 0.3, largeChunk);
        BudgetKnowledgeItemDto kb2 = kbItem("kb_2", 0.9, largeChunk);
        BudgetHistoryItemDto hist1 = historyItem("hist_1", 1, 1L, "Short history turn", null);

        ContextBudgetResultDto kbOnlyEvicted =
                budgetManager.enforceBudget(
                        envelope, List.of(hist1), List.of(kb1, kb2), List.of(), true, 32_000);
        assertTrue(kbOnlyEvicted.isPruningApplied());
        assertFalse(kbOnlyEvicted.isHistoryTruncated());
        assertFalse(kbOnlyEvicted.isRolloverSuggested());

        // Sub-Branch with history truncated -> rolloverSuggested MUST remain false
        BudgetHistoryItemDto histOld = historyItem("hist_old", 1, 1L, largeChunk, null);
        BudgetHistoryItemDto histNew = historyItem("hist_new", 1, 2L, largeChunk, null);
        ContextBudgetResultDto subBranchResult =
                budgetManager.enforceBudget(
                        envelope, List.of(histOld, histNew), List.of(), List.of(), false, 32_000);
        assertTrue(subBranchResult.isHistoryTruncated());
        assertFalse(subBranchResult.isRolloverSuggested());
    }

    @Test
    void reusesApprovedAncestorChapterSummaryWhenRawChapterHistoryIsEvicted() {
        UUID chapterId = UUID.randomUUID();
        String hugeTurn = "t".repeat(120_000); // ~34,500 tokens with 1.15x
        PromptEnvelopeDto envelope = minimalEnvelope();
        BudgetHistoryItemDto evictedTurn = historyItem("hist_1", 1, 1L, hugeTurn, chapterId);
        ChapterSummaryRecord summary =
                ChapterSummaryRecord.builder()
                        .summaryId(UUID.randomUUID())
                        .chapterId(chapterId)
                        .mainStageId(UUID.randomUUID())
                        .stageOrder(1)
                        .startSequenceNo(1L)
                        .endSequenceNo(1L)
                        .chapterTitle("Chapter 1")
                        .summaryText("Approved compact summary")
                        .approvalStatus("USER_APPROVED")
                        .sourceRevisionSnapshot(1L)
                        .createdAt(Instant.parse("2026-11-26T00:00:00Z"))
                        .build();

        ContextBudgetResultDto result =
                budgetManager.enforceBudget(
                        envelope, List.of(evictedTurn), List.of(), List.of(summary), true, 32_000);

        assertTrue(result.isHistoryTruncated());
        assertTrue(result.isRolloverSuggested());
        assertEquals(1, result.getPromptEnvelope().getKnowledgeAndEvidence().getHistory().size());
        assertEquals(
                "CHAPTER_SUMMARY",
                result.getPromptEnvelope()
                        .getKnowledgeAndEvidence()
                        .getHistory()
                        .getFirst()
                        .getSourceType());
    }

    private static PromptEnvelopeDto minimalEnvelope() {
        return PromptEnvelopeDto.builder()
                .platformPolicy("Policy")
                .currentUserRequest(
                        CurrentUserRequestDto.builder()
                                .contentBlocks(List.of(ContentBlockDto.textBlock("Hi")))
                                .build())
                .intelligenceContext(
                        IntelligenceContextDto.builder()
                                .background(IntelligenceBackgroundDto.builder().build())
                                .instructions(IntelligenceInstructionsDto.builder().build())
                                .build())
                .knowledgeAndEvidence(KnowledgeAndEvidenceDto.builder().build())
                .responseInstructions(new ResponseInstructionsDto())
                .build();
    }

    private static BudgetKnowledgeItemDto kbItem(String ref, double score, String content) {
        return BudgetKnowledgeItemDto.builder()
                .knowledgeItemId(UUID.randomUUID())
                .relevanceScore(score)
                .createdAt(Instant.parse("2026-11-26T00:00:00Z"))
                .sourceItem(
                        ContextSourceItemDto.builder()
                                .sourceRef(ref)
                                .sourceType("KB_CHUNK")
                                .content(content)
                                .authority("PROJECT_KB")
                                .sourceRevision("1")
                                .sourceLinkStatus("AVAILABLE")
                                .build())
                .build();
    }

    private static BudgetHistoryItemDto historyItem(
            String ref, int stageOrder, long seq, String content, UUID chapterId) {
        return BudgetHistoryItemDto.builder()
                .turnId(UUID.randomUUID())
                .mainStageId(UUID.randomUUID())
                .chapterId(chapterId)
                .stageOrder(stageOrder)
                .sequenceNo(seq)
                .sourceRevision(1L)
                .createdAt(Instant.parse("2026-11-26T00:00:00Z"))
                .sourceItem(
                        ContextSourceItemDto.builder()
                                .sourceRef(ref)
                                .sourceType("MAIN_TURN")
                                .content(content)
                                .authority("CONVERSATION_HISTORY")
                                .sourceRevision("1")
                                .sourceLinkStatus("AVAILABLE")
                                .build())
                .build();
    }
}

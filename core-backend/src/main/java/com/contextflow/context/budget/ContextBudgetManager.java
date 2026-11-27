package com.contextflow.context.budget;

import com.contextflow.context.dto.BudgetHistoryItemDto;
import com.contextflow.context.dto.BudgetKnowledgeItemDto;
import com.contextflow.context.dto.ContextBudgetResultDto;
import com.contextflow.context.dto.ContextSourceItemDto;
import com.contextflow.context.dto.InstructionItemDto;
import com.contextflow.context.dto.IntelligenceBackgroundDto;
import com.contextflow.context.dto.IntelligenceContextDto;
import com.contextflow.context.dto.IntelligenceInstructionsDto;
import com.contextflow.context.dto.KnowledgeAndEvidenceDto;
import com.contextflow.context.dto.PromptEnvelopeDto;
import com.contextflow.context.repository.ChapterSummaryRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Enforces the 32,000-token SoftCap using a 1.15x local safety multiplier.
 *
 * <p>Eviction order when estimated tokens exceed SoftCap: 1) lowest-relevance KB items; 2) oldest
 * non-active history turns (replacing evicted ancestor chapters with approved CHAPTER_SUMMARY
 * entries if available and within budget); 3) optional soft Block C elements (personal
 * profile/instructions, skills, project information, overridable rules).
 *
 * <p>NEVER truncates Block A (platformPolicy), Block B (currentUserRequest), explicitReferences,
 * mandatoryRules, source_of_truth, responseInstructions, or the Active Window continuity chain.
 */
@Component
@RequiredArgsConstructor
public class ContextBudgetManager {
    public static final int DEFAULT_SOFT_CAP_TOKENS = 32_000;

    private final LocalTokenEstimator tokenEstimator;

    public ContextBudgetResultDto enforceBudget(
            PromptEnvelopeDto envelope,
            List<BudgetHistoryItemDto> historyItems,
            List<BudgetKnowledgeItemDto> knowledgeItems,
            List<ChapterSummaryRecord> approvedAncestorSummaries,
            boolean mainStage,
            int configuredSoftCap) {
        int softCap = configuredSoftCap > 0 ? configuredSoftCap : DEFAULT_SOFT_CAP_TOKENS;

        List<BudgetHistoryItemDto> survivingHistory =
                new ArrayList<>(historyItems == null ? List.of() : historyItems);
        List<BudgetKnowledgeItemDto> survivingKb =
                new ArrayList<>(knowledgeItems == null ? List.of() : knowledgeItems);

        PromptEnvelopeDto working = copyEnvelope(envelope, survivingHistory, survivingKb);
        int estimatedBefore = tokenEstimator.estimateEnvelopeTokens(working);
        int currentTokens = estimatedBefore;

        boolean pruningApplied = false;
        boolean historyTruncated = false;

        if (currentTokens > softCap && !survivingKb.isEmpty()) {
            List<BudgetKnowledgeItemDto> kbEvictionOrder = new ArrayList<>(survivingKb);
            kbEvictionOrder.sort(
                    Comparator.comparingDouble(BudgetKnowledgeItemDto::getRelevanceScore)
                            .thenComparing(
                                    BudgetKnowledgeItemDto::getCreatedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(
                                    item -> item.getSourceItem().getSourceRef(),
                                    Comparator.nullsLast(Comparator.naturalOrder())));

            Set<BudgetKnowledgeItemDto> evictedKb = new HashSet<>();
            for (BudgetKnowledgeItemDto candidate : kbEvictionOrder) {
                if (currentTokens <= softCap) {
                    break;
                }
                evictedKb.add(candidate);
                survivingKb.remove(candidate);
                pruningApplied = true;
                working.getKnowledgeAndEvidence().setKnowledgeBase(toSourceItemsKb(survivingKb));
                currentTokens = tokenEstimator.estimateEnvelopeTokens(working);
            }
        }

        List<BudgetHistoryItemDto> evictedHistoryItems = new ArrayList<>();
        if (currentTokens > softCap && !survivingHistory.isEmpty()) {
            List<BudgetHistoryItemDto> historyEvictionOrder = new ArrayList<>(survivingHistory);
            historyEvictionOrder.sort(
                    Comparator.comparingInt(BudgetHistoryItemDto::getStageOrder)
                            .thenComparingLong(BudgetHistoryItemDto::getSequenceNo)
                            .thenComparing(
                                    BudgetHistoryItemDto::getCreatedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(
                                    item -> item.getSourceItem().getSourceRef(),
                                    Comparator.nullsLast(Comparator.naturalOrder())));

            for (BudgetHistoryItemDto candidate : historyEvictionOrder) {
                if (currentTokens <= softCap) {
                    break;
                }
                evictedHistoryItems.add(candidate);
                survivingHistory.remove(candidate);
                pruningApplied = true;
                historyTruncated = true;
                working.getKnowledgeAndEvidence()
                        .setHistory(toSourceItemsHistory(survivingHistory));
                currentTokens = tokenEstimator.estimateEnvelopeTokens(working);
            }
        }

        if (historyTruncated
                && approvedAncestorSummaries != null
                && !approvedAncestorSummaries.isEmpty()) {
            applyEligibleChapterSummaries(
                    working,
                    survivingHistory,
                    evictedHistoryItems,
                    approvedAncestorSummaries,
                    softCap);
            currentTokens = tokenEstimator.estimateEnvelopeTokens(working);
        }

        if (currentTokens > softCap) {
            pruningApplied |= trimSoftIntelligence(working, softCap);
            currentTokens = tokenEstimator.estimateEnvelopeTokens(working);
        }

        boolean overBudget = currentTokens > softCap;
        boolean rolloverSuggested = mainStage && (historyTruncated || overBudget);

        List<UUID> retainedHistoryIds = new ArrayList<>();
        for (BudgetHistoryItemDto item : survivingHistory) {
            if (item.getTurnId() != null) {
                retainedHistoryIds.add(item.getTurnId());
            }
        }
        List<UUID> retainedKbIds = new ArrayList<>();
        for (BudgetKnowledgeItemDto item : survivingKb) {
            if (item.getKnowledgeItemId() != null) {
                retainedKbIds.add(item.getKnowledgeItemId());
            }
        }

        return ContextBudgetResultDto.builder()
                .promptEnvelope(working)
                .estimatedTokensBeforeTruncation(estimatedBefore)
                .estimatedInputTokens(currentTokens)
                .softCapTokens(softCap)
                .safetyMultiplier(LocalTokenEstimator.SAFETY_MULTIPLIER)
                .pruningApplied(pruningApplied)
                .historyTruncated(historyTruncated)
                .overBudget(overBudget)
                .rolloverSuggested(rolloverSuggested)
                .retainedHistoryTurnIds(retainedHistoryIds)
                .retainedKnowledgeItemIds(retainedKbIds)
                .build();
    }

    private void applyEligibleChapterSummaries(
            PromptEnvelopeDto working,
            List<BudgetHistoryItemDto> survivingHistory,
            List<BudgetHistoryItemDto> evictedHistoryItems,
            List<ChapterSummaryRecord> summaries,
            int softCap) {
        Set<UUID> evictedChapterIds = new HashSet<>();
        for (BudgetHistoryItemDto evicted : evictedHistoryItems) {
            if (evicted.getChapterId() != null) {
                evictedChapterIds.add(evicted.getChapterId());
            }
        }
        if (evictedChapterIds.isEmpty()) {
            return;
        }
        Map<UUID, ChapterSummaryRecord> bestByChapter = new HashMap<>();
        for (ChapterSummaryRecord record : summaries) {
            if (record.getChapterId() != null
                    && evictedChapterIds.contains(record.getChapterId())) {
                bestByChapter.putIfAbsent(record.getChapterId(), record);
            }
        }
        int summarySeq = 1;
        for (ChapterSummaryRecord summary : bestByChapter.values()) {
            ContextSourceItemDto summarySource =
                    ContextSourceItemDto.builder()
                            .sourceRef("chsum_" + summarySeq++)
                            .sourceType("CHAPTER_SUMMARY")
                            .content(summary.getChapterTitle() + ": " + summary.getSummaryText())
                            .authority("CONVERSATION_HISTORY")
                            .sourceRevision(String.valueOf(summary.getSourceRevisionSnapshot()))
                            .sourceLinkStatus("AVAILABLE")
                            .build();
            BudgetHistoryItemDto candidate =
                    BudgetHistoryItemDto.builder()
                            .mainStageId(summary.getMainStageId())
                            .chapterId(summary.getChapterId())
                            .stageOrder(summary.getStageOrder())
                            .sequenceNo(
                                    summary.getEndSequenceNo() != null
                                            ? summary.getEndSequenceNo()
                                            : 0L)
                            .sourceRevision(summary.getSourceRevisionSnapshot())
                            .createdAt(summary.getCreatedAt())
                            .chapterSummary(true)
                            .sourceItem(summarySource)
                            .build();

            survivingHistory.addFirst(candidate);
            working.getKnowledgeAndEvidence().setHistory(toSourceItemsHistory(survivingHistory));
            if (tokenEstimator.estimateEnvelopeTokens(working) > softCap) {
                survivingHistory.removeFirst();
                working.getKnowledgeAndEvidence()
                        .setHistory(toSourceItemsHistory(survivingHistory));
            }
        }
    }

    private boolean trimSoftIntelligence(PromptEnvelopeDto working, int softCap) {
        IntelligenceContextDto intel = working.getIntelligenceContext();
        if (intel == null) {
            return false;
        }
        boolean trimmed = false;
        IntelligenceBackgroundDto bg = intel.getBackground();
        IntelligenceInstructionsDto ins = intel.getInstructions();

        if (bg != null && bg.getPersonalProfile() != null) {
            bg.setPersonalProfile(null);
            trimmed = true;
            if (tokenEstimator.estimateEnvelopeTokens(working) <= softCap) {
                return true;
            }
        }
        if (ins != null && ins.getPersonalInstructions() != null) {
            ins.setPersonalInstructions(null);
            trimmed = true;
            if (tokenEstimator.estimateEnvelopeTokens(working) <= softCap) {
                return true;
            }
        }
        if (bg != null && bg.getProjectInformation() != null) {
            bg.setProjectInformation(null);
            trimmed = true;
            if (tokenEstimator.estimateEnvelopeTokens(working) <= softCap) {
                return true;
            }
        }
        if (ins != null && ins.getActiveSkills() != null && !ins.getActiveSkills().isEmpty()) {
            List<InstructionItemDto> skills = new ArrayList<>(ins.getActiveSkills());
            skills.sort(Comparator.comparingInt(InstructionItemDto::getPriority).reversed());
            for (InstructionItemDto skill : skills) {
                if (tokenEstimator.estimateEnvelopeTokens(working) <= softCap) {
                    break;
                }
                ins.getActiveSkills().remove(skill);
                trimmed = true;
            }
        }
        if (ins != null
                && ins.getOverridableRules() != null
                && !ins.getOverridableRules().isEmpty()
                && tokenEstimator.estimateEnvelopeTokens(working) > softCap) {
            List<InstructionItemDto> overridable = new ArrayList<>(ins.getOverridableRules());
            overridable.sort(Comparator.comparingInt(InstructionItemDto::getPriority).reversed());
            for (InstructionItemDto rule : overridable) {
                if (tokenEstimator.estimateEnvelopeTokens(working) <= softCap) {
                    break;
                }
                ins.getOverridableRules().remove(rule);
                trimmed = true;
            }
        }
        return trimmed;
    }

    private static PromptEnvelopeDto copyEnvelope(
            PromptEnvelopeDto source,
            List<BudgetHistoryItemDto> historyItems,
            List<BudgetKnowledgeItemDto> kbItems) {
        IntelligenceContextDto srcIntel = source.getIntelligenceContext();
        IntelligenceContextDto copiedIntel =
                IntelligenceContextDto.builder()
                        .background(
                                IntelligenceBackgroundDto.builder()
                                        .projectInformation(
                                                srcIntel.getBackground().getProjectInformation())
                                        .personalProfile(
                                                srcIntel.getBackground().getPersonalProfile())
                                        .build())
                        .instructions(
                                IntelligenceInstructionsDto.builder()
                                        .mandatoryRules(
                                                new ArrayList<>(
                                                        srcIntel.getInstructions()
                                                                .getMandatoryRules()))
                                        .overridableRules(
                                                new ArrayList<>(
                                                        srcIntel.getInstructions()
                                                                .getOverridableRules()))
                                        .activeSkills(
                                                new ArrayList<>(
                                                        srcIntel.getInstructions()
                                                                .getActiveSkills()))
                                        .personalInstructions(
                                                srcIntel.getInstructions()
                                                        .getPersonalInstructions())
                                        .build())
                        .build();

        KnowledgeAndEvidenceDto srcKe = source.getKnowledgeAndEvidence();
        KnowledgeAndEvidenceDto copiedKe =
                KnowledgeAndEvidenceDto.builder()
                        .history(toSourceItemsHistory(historyItems))
                        .knowledgeBase(toSourceItemsKb(kbItems))
                        .sourceOfTruth(new ArrayList<>(srcKe.getSourceOfTruth()))
                        .explicitReferences(new ArrayList<>(srcKe.getExplicitReferences()))
                        .build();

        return PromptEnvelopeDto.builder()
                .platformPolicy(source.getPlatformPolicy())
                .currentUserRequest(source.getCurrentUserRequest())
                .intelligenceContext(copiedIntel)
                .knowledgeAndEvidence(copiedKe)
                .responseInstructions(source.getResponseInstructions())
                .continuity(new ArrayList<>(source.getContinuity()))
                .build();
    }

    private static List<ContextSourceItemDto> toSourceItemsHistory(
            List<BudgetHistoryItemDto> items) {
        List<ContextSourceItemDto> result = new ArrayList<>(items.size());
        for (BudgetHistoryItemDto item : items) {
            result.add(item.getSourceItem());
        }
        return result;
    }

    private static List<ContextSourceItemDto> toSourceItemsKb(List<BudgetKnowledgeItemDto> items) {
        List<ContextSourceItemDto> result = new ArrayList<>(items.size());
        for (BudgetKnowledgeItemDto item : items) {
            result.add(item.getSourceItem());
        }
        return result;
    }
}

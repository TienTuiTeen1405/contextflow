package com.contextflow.context.compiler;

import com.contextflow.context.dto.BudgetExplicitRefDto;
import com.contextflow.context.dto.BudgetHistoryItemDto;
import com.contextflow.context.dto.BudgetKnowledgeItemDto;
import com.contextflow.context.dto.ContinuityTurnDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic 4-tier deduplicator across: (1) Quote / Explicit Reference -> (2) Active Window ->
 * (3) Turn / Ancestor History -> (4) Knowledge Base.
 *
 * <p>Preserves Active Window intact when overlapping short Quotes (CF-DEC-034) and preserves
 * independent KB items such as CONDENSED_CONTEXT_NOTE even when referencing an older turn
 * (CF-DEC-031), dropping only duplicate turn projections and duplicate normalized content.
 */
@Component
public class ContextDeduplicator {

    public DeduplicationOutcome deduplicate(
            List<BudgetExplicitRefDto> rawExplicitRefs,
            List<ContinuityTurnDto> activeWindowTurns,
            Set<UUID> activeWindowTurnIds,
            List<BudgetHistoryItemDto> rawHistoryItems,
            List<BudgetKnowledgeItemDto> rawKnowledgeItems) {

        List<BudgetExplicitRefDto> dedupExplicit = new ArrayList<>();
        Map<String, String> canonicalAliasByExplicitKey = new HashMap<>();
        Map<String, String> explicitReferenceAliases = new HashMap<>();
        Set<UUID> explicitMainRefTurnIds = new HashSet<>();
        Set<UUID> explicitMainRefStageIds = new HashSet<>();
        Set<UUID> explicitQuoteSourceIds = new HashSet<>();
        Set<String> explicitNormalizedTexts = new HashSet<>();
        int droppedExplicit = 0;

        if (rawExplicitRefs != null) {
            for (BudgetExplicitRefDto ref : rawExplicitRefs) {
                if (ref == null || ref.getExplicitReference() == null) {
                    continue;
                }
                var wire = ref.getExplicitReference();
                String normText = normalize(wire.getSnapshotText());
                String key = buildExplicitDedupKey(ref, normText);
                if (canonicalAliasByExplicitKey.containsKey(key)) {
                    String canonicalAlias = canonicalAliasByExplicitKey.get(key);
                    if (wire.getId() != null
                            && canonicalAlias != null
                            && !wire.getId().equals(canonicalAlias)) {
                        explicitReferenceAliases.put(wire.getId(), canonicalAlias);
                    }
                    droppedExplicit++;
                    continue;
                }
                canonicalAliasByExplicitKey.put(key, wire.getId());
                dedupExplicit.add(ref);
                if (!normText.isEmpty()) {
                    explicitNormalizedTexts.add(normText);
                }
                if ("MAIN_REF".equals(wire.getKind())) {
                    if (ref.getTargetTurnId() != null) {
                        explicitMainRefTurnIds.add(ref.getTargetTurnId());
                    } else if (ref.getTargetStageId() != null && ref.getTargetChapterId() == null) {
                        explicitMainRefStageIds.add(ref.getTargetStageId());
                    } else if (ref.getSourceId() != null
                            && ("TURN".equals(wire.getSourceType())
                                    || "MAIN_TURN".equals(wire.getSourceType()))) {
                        explicitMainRefTurnIds.add(ref.getSourceId());
                    }
                } else if ("QUOTE".equals(wire.getKind()) && ref.getSourceId() != null) {
                    explicitQuoteSourceIds.add(ref.getSourceId());
                }
            }
        }

        // Tier 2: Active Window is preserved intact; track its Turn IDs and normalized prompt text.
        Set<UUID> activeTurnIds =
                activeWindowTurnIds == null ? Set.of() : new HashSet<>(activeWindowTurnIds);
        Set<String> activeWindowTexts = new HashSet<>();
        if (activeWindowTurns != null) {
            for (ContinuityTurnDto turn : activeWindowTurns) {
                if (turn.getUserTextBlocks() != null) {
                    String joined = normalize(String.join(" ", turn.getUserTextBlocks()));
                    if (!joined.isEmpty()) {
                        activeWindowTexts.add(joined);
                    }
                }
            }
        }

        // Tier 3: Turn / Ancestor History. Deduplicate within Tier 3 keeping fresher
        // revision/timestamp,
        // and drop items already covered by Tier 1 MAIN_REF or Tier 2 Active Window.
        int droppedHistory = 0;
        Map<UUID, BudgetHistoryItemDto> bestHistoryByTurnId = new HashMap<>();
        List<BudgetHistoryItemDto> historyWithoutTurnId = new ArrayList<>();
        if (rawHistoryItems != null) {
            for (BudgetHistoryItemDto item : rawHistoryItems) {
                if (item == null || item.getSourceItem() == null) {
                    continue;
                }
                UUID turnId = item.getTurnId();
                if (turnId != null) {
                    if (activeTurnIds.contains(turnId)
                            || explicitMainRefTurnIds.contains(turnId)
                            || (item.getMainStageId() != null
                                    && explicitMainRefStageIds.contains(item.getMainStageId()))) {
                        droppedHistory++;
                        continue;
                    }
                    BudgetHistoryItemDto existing = bestHistoryByTurnId.get(turnId);
                    if (existing == null) {
                        bestHistoryByTurnId.put(turnId, item);
                    } else {
                        droppedHistory++;
                        if (isFresherHistory(item, existing)) {
                            bestHistoryByTurnId.put(turnId, item);
                        }
                    }
                } else {
                    historyWithoutTurnId.add(item);
                }
            }
        }

        List<BudgetHistoryItemDto> candidateHistory = new ArrayList<>(bestHistoryByTurnId.values());
        candidateHistory.addAll(historyWithoutTurnId);
        candidateHistory.sort(
                Comparator.comparingInt(BudgetHistoryItemDto::getStageOrder)
                        .thenComparingLong(BudgetHistoryItemDto::getSequenceNo)
                        .thenComparing(
                                BudgetHistoryItemDto::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(
                                BudgetHistoryItemDto::getTurnId,
                                Comparator.nullsLast(Comparator.naturalOrder())));

        List<BudgetHistoryItemDto> dedupHistory = new ArrayList<>();
        Set<UUID> retainedHistoryTurnIds = new HashSet<>();
        Set<String> retainedHistoryTexts = new HashSet<>();
        for (BudgetHistoryItemDto item : candidateHistory) {
            String norm = normalize(item.getSourceItem().getContent());
            if (!norm.isEmpty()
                    && (explicitNormalizedTexts.contains(norm)
                            || activeWindowTexts.contains(norm)
                            || !retainedHistoryTexts.add(norm))) {
                droppedHistory++;
                continue;
            }
            dedupHistory.add(item);
            if (item.getTurnId() != null) {
                retainedHistoryTurnIds.add(item.getTurnId());
            }
        }

        // Tier 4: Knowledge Base items. Deduplicate within Tier 4 keeping fresher
        // revision/timestamp,
        // and drop items superseded by Tier 1, Tier 2, or Tier 3.
        int droppedKnowledge = 0;
        Map<String, BudgetKnowledgeItemDto> bestKbByIdentity = new HashMap<>();
        if (rawKnowledgeItems != null) {
            for (BudgetKnowledgeItemDto item : rawKnowledgeItems) {
                if (item == null || item.getSourceItem() == null) {
                    continue;
                }
                if (isSupersededByHigherTiers(
                        item,
                        explicitMainRefTurnIds,
                        explicitQuoteSourceIds,
                        explicitNormalizedTexts,
                        activeTurnIds,
                        activeWindowTexts,
                        retainedHistoryTurnIds,
                        retainedHistoryTexts)) {
                    droppedKnowledge++;
                    continue;
                }
                String identityKey = buildKnowledgeIdentityKey(item);
                BudgetKnowledgeItemDto existing = bestKbByIdentity.get(identityKey);
                if (existing == null) {
                    bestKbByIdentity.put(identityKey, item);
                } else {
                    droppedKnowledge++;
                    if (isFresherKnowledge(item, existing)) {
                        bestKbByIdentity.put(identityKey, item);
                    }
                }
            }
        }

        List<BudgetKnowledgeItemDto> sortedKb = new ArrayList<>(bestKbByIdentity.values());
        sortedKb.sort(
                Comparator.comparingDouble(BudgetKnowledgeItemDto::getRelevanceScore)
                        .reversed()
                        .thenComparing(
                                Comparator.comparing(
                                                BudgetKnowledgeItemDto::getCreatedAt,
                                                Comparator.nullsLast(Comparator.naturalOrder()))
                                        .reversed())
                        .thenComparing(
                                BudgetKnowledgeItemDto::getKnowledgeItemId,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(
                                BudgetKnowledgeItemDto::getChunkId,
                                Comparator.nullsLast(Comparator.naturalOrder())));

        List<BudgetKnowledgeItemDto> dedupKb = new ArrayList<>();
        Set<String> seenKbTexts = new HashSet<>();
        for (BudgetKnowledgeItemDto item : sortedKb) {
            String norm = normalize(item.getSourceItem().getContent());
            if (!norm.isEmpty() && !seenKbTexts.add(norm)) {
                droppedKnowledge++;
                continue;
            }
            dedupKb.add(item);
        }

        return DeduplicationOutcome.builder()
                .explicitReferences(dedupExplicit)
                .explicitReferenceAliases(explicitReferenceAliases)
                .historyItems(dedupHistory)
                .knowledgeItems(dedupKb)
                .droppedExplicitDuplicates(droppedExplicit)
                .droppedHistoryDuplicates(droppedHistory)
                .droppedKnowledgeDuplicates(droppedKnowledge)
                .build();
    }

    private static boolean isSupersededByHigherTiers(
            BudgetKnowledgeItemDto item,
            Set<UUID> explicitMainRefTurnIds,
            Set<UUID> explicitQuoteSourceIds,
            Set<String> explicitNormalizedTexts,
            Set<UUID> activeTurnIds,
            Set<String> activeWindowTexts,
            Set<UUID> retainedHistoryTurnIds,
            Set<String> retainedHistoryTexts) {
        String normContent = normalize(item.getSourceItem().getContent());
        if (!normContent.isEmpty()
                && (explicitNormalizedTexts.contains(normContent)
                        || activeWindowTexts.contains(normContent)
                        || retainedHistoryTexts.contains(normContent))) {
            return true;
        }
        boolean isMainTurnProjection =
                "MAIN_TURN".equals(item.getItemType())
                        || "MAIN_TURN".equals(item.getSourceItem().getSourceType());
        if (isMainTurnProjection && item.getSourceTurnId() != null) {
            UUID sourceTurnId = item.getSourceTurnId();
            if (explicitMainRefTurnIds.contains(sourceTurnId)
                    || activeTurnIds.contains(sourceTurnId)
                    || retainedHistoryTurnIds.contains(sourceTurnId)) {
                return true;
            }
        }
        if ("PROMOTED_QUOTE".equals(item.getItemType())
                && item.getPromotedFromQuoteRefId() != null
                && explicitQuoteSourceIds.contains(item.getPromotedFromQuoteRefId())) {
            return true;
        }
        return false;
    }

    private static String buildExplicitDedupKey(BudgetExplicitRefDto ref, String normText) {
        var wire = ref.getExplicitReference();
        if ("MAIN_REF".equals(wire.getKind())) {
            return "MAIN_REF|"
                    + ref.getTargetStageId()
                    + "|"
                    + ref.getTargetChapterId()
                    + "|"
                    + ref.getTargetTurnId()
                    + "|"
                    + normText;
        }
        if (ref.getSourceId() != null) {
            return wire.getKind() + "|" + ref.getSourceId() + "|" + normText;
        }
        return wire.getKind() + "|" + wire.getSourceType() + "|" + normText;
    }

    private static String buildKnowledgeIdentityKey(BudgetKnowledgeItemDto item) {
        if (item.getChunkId() != null) {
            return "CHUNK|" + item.getChunkId();
        }
        if (item.getKnowledgeItemId() != null) {
            return "ITEM|" + item.getKnowledgeItemId();
        }
        String norm = normalize(item.getSourceItem().getContent());
        return "CONTENT|" + norm;
    }

    private static boolean isFresherHistory(
            BudgetHistoryItemDto candidate, BudgetHistoryItemDto existing) {
        if (candidate.getSourceRevision() != existing.getSourceRevision()) {
            return candidate.getSourceRevision() > existing.getSourceRevision();
        }
        if (candidate.getCreatedAt() != null && existing.getCreatedAt() != null) {
            int cmp = candidate.getCreatedAt().compareTo(existing.getCreatedAt());
            if (cmp != 0) {
                return cmp > 0;
            }
        }
        return candidate.getSequenceNo() > existing.getSequenceNo();
    }

    private static boolean isFresherKnowledge(
            BudgetKnowledgeItemDto candidate, BudgetKnowledgeItemDto existing) {
        long candRev = parseRevision(candidate.getSourceItem().getSourceRevision());
        long existRev = parseRevision(existing.getSourceItem().getSourceRevision());
        if (candRev != existRev) {
            return candRev > existRev;
        }
        if (candidate.getCreatedAt() != null && existing.getCreatedAt() != null) {
            int cmp = candidate.getCreatedAt().compareTo(existing.getCreatedAt());
            if (cmp != 0) {
                return cmp > 0;
            }
        }
        return candidate.getRelevanceScore() > existing.getRelevanceScore();
    }

    private static long parseRevision(String revision) {
        if (revision == null || revision.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(revision.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}

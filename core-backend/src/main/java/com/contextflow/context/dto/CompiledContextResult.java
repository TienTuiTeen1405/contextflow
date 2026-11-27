package com.contextflow.context.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompiledContextResult {
    private UUID projectId;
    private UUID conversationId;
    private UUID mainStageId;
    private UUID subBranchId;
    private boolean personalIntelligenceEnabled;
    private boolean projectIntelligenceEnabled;
    private int mandatoryRulesCount;
    private int overridableRulesCount;
    private int activeSkillsCount;
    private int activeWindowTurnsCount;
    private int historyTurnsCount;
    private int estimatedInputTokens;
    private int estimatedTokensBeforeTruncation;
    private int softCapTokens;
    private double safetyMultiplier;
    private boolean pruningApplied;
    private boolean historyTruncated;
    private boolean overBudget;
    private boolean rolloverSuggested;
    private PrivacyTier effectivePrivacyTier;
    private boolean mixedTierWarning;
    private PromptEnvelopeDto promptEnvelope;

    @Builder.Default private List<UUID> activeWindowTurnIds = new ArrayList<>();

    @Builder.Default private List<UUID> historyTurnIds = new ArrayList<>();

    @Builder.Default private List<UUID> knowledgeItemIds = new ArrayList<>();

    @Builder.Default private List<UUID> sotDecisionIds = new ArrayList<>();
}

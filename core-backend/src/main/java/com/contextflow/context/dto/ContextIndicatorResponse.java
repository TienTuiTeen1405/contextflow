package com.contextflow.context.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextIndicatorResponse {
    private UUID projectId;
    private UUID conversationId;
    private UUID mainStageId;
    private UUID subBranchId;
    private boolean personalIntelligenceEnabled;
    private boolean projectIntelligenceEnabled;
    private int mandatoryRules;
    private int overridableRules;
    private int activeSkills;
    private int activeWindowTurns;
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
}

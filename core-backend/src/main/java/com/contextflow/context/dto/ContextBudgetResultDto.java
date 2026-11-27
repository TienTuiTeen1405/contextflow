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
public class ContextBudgetResultDto {
    private PromptEnvelopeDto promptEnvelope;
    private int estimatedTokensBeforeTruncation;
    private int estimatedInputTokens;
    private int softCapTokens;
    private double safetyMultiplier;
    private boolean pruningApplied;
    private boolean historyTruncated;
    private boolean overBudget;
    private boolean rolloverSuggested;

    @Builder.Default private List<UUID> retainedHistoryTurnIds = new ArrayList<>();

    @Builder.Default private List<UUID> retainedKnowledgeItemIds = new ArrayList<>();
}

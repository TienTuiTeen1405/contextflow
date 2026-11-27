package com.contextflow.context.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetKnowledgeItemDto {
    private UUID knowledgeItemId;
    private UUID chunkId;
    private UUID sourceTurnId;
    private UUID promotedFromQuoteRefId;
    private String itemType;
    private double relevanceScore;
    private Instant createdAt;
    private ContextSourceItemDto sourceItem;
}

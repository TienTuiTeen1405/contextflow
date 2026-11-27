package com.contextflow.knowledge.dto;

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
public class KnowledgeIngestionResult {
    private UUID knowledgeItemId;
    private UUID indexGenerationId;
    @Builder.Default private List<UUID> chunkIds = new ArrayList<>();
    @Builder.Default private List<UUID> outboxIds = new ArrayList<>();
    private int excludedQuoteCount;
    private boolean skipped;
}

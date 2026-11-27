package com.contextflow.knowledge.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimedOutboxTask {
    private UUID outboxId;
    private UUID projectId;
    private UUID knowledgeItemId;
    private UUID chunkId;
    private UUID indexGenerationId;
    private long sourceRevision;
    private String embeddingModel;
    private int outputDimensionality;
    private String content;
    private String contentHash;
    private int estimatedTokens;
    private int attemptCount;
    private UUID workerToken;
}

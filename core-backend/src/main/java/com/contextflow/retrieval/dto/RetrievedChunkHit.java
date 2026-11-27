package com.contextflow.retrieval.dto;

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
public class RetrievedChunkHit {
    private UUID chunkId;
    private UUID knowledgeItemId;
    private UUID sourceTurnId;
    private UUID promotedFromQuoteRefId;
    private String itemType;
    private String sourceType;
    private String title;
    private String content;
    private String sourceRevision;
    private Instant createdAt;
    private double rawScore;
    private String branchName;
}

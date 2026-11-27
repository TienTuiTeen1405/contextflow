package com.contextflow.context.dto;

import java.time.Instant;
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
public class KnowledgeCandidateDto {
    private UUID knowledgeItemId;
    private UUID chunkId;
    private UUID sourceTurnId;
    private UUID promotedFromQuoteRefId;
    private String itemType;
    private String sourceType;
    private String title;
    private String content;

    @Builder.Default private String sourceRevision = "1";

    @Builder.Default private String linkStatus = "AVAILABLE";

    private double relevanceScore;
    private Instant createdAt;

    @Builder.Default private List<String> matchedBranches = new ArrayList<>();
}

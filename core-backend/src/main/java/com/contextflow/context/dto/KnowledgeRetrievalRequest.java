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
public class KnowledgeRetrievalRequest {
    private UUID projectId;
    private UUID conversationId;
    private UUID mainStageId;
    private UUID subBranchId;
    private String queryText;

    @Builder.Default private IntentContextDto intentContext = new IntentContextDto();

    @Builder.Default private int maxCandidates = 20;
}

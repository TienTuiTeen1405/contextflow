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
public class TurnIngestionRequest {
    private UUID projectId;
    private UUID conversationId;
    private UUID mainStageId;
    private UUID subBranchId;
    private UUID turnId;
    @Builder.Default private long sourceRevision = 1L;
    private String title;

    @Builder.Default private List<TurnContentBlockDto> userContentBlocks = new ArrayList<>();

    @Builder.Default private List<TurnQuoteExclusionDto> quoteSnapshots = new ArrayList<>();

    private String assistantText;
}

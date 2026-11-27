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
public class BudgetHistoryItemDto {
    private UUID turnId;
    private UUID mainStageId;
    private UUID chapterId;
    private int stageOrder;
    private long sequenceNo;
    private long sourceRevision;
    private Instant createdAt;
    private boolean chapterSummary;
    private ContextSourceItemDto sourceItem;
}

package com.contextflow.context.repository;

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
public class ChapterSummaryRecord {
    private UUID summaryId;
    private UUID chapterId;
    private UUID mainStageId;
    private int stageOrder;
    private Long startSequenceNo;
    private Long endSequenceNo;
    private String chapterTitle;
    private String summaryText;
    private String approvalStatus;
    private long sourceRevisionSnapshot;
    private Instant createdAt;
}

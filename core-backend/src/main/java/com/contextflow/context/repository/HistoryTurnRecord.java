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
public class HistoryTurnRecord {
    private UUID turnId;
    private UUID mainStageId;
    private int stageOrder;
    private UUID chapterId;
    private long sequenceNo;
    private long sourceRevision;
    private String userText;
    private String assistantText;
    private Instant createdAt;
}

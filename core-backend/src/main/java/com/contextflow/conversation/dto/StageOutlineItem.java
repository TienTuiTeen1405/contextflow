package com.contextflow.conversation.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One Main Stage in the conversation outline (contract schema {@code StageView}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StageOutlineItem {
    private UUID id;
    private UUID parentStageId;
    private int stageOrder;
    private String title;
    private MainStageStatus status;
    private Instant createdAt;
}

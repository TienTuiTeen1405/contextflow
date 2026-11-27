package com.contextflow.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxBatchOutcome {
    private int claimedCount;
    private int completedCount;
    private int retriedCount;
    private int blockedCount;
    private int failedCount;
    private int cancelledCount;
    private boolean throttled;
}

package com.contextflow.conversation.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code @Main} reference saved with a Turn (contract schema {@code MainReference}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MainReferenceResponse {
    private ReferenceGranularity granularity;
    private UUID targetStageId;
    private UUID targetChapterId;
    private UUID targetTurnId;
}

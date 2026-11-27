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
public class BudgetExplicitRefDto {
    private UUID sourceId;
    private UUID targetStageId;
    private UUID targetChapterId;
    private UUID targetTurnId;
    private ExplicitReferenceItemDto explicitReference;
}

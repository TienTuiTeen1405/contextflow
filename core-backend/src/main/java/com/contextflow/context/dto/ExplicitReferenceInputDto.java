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
public class ExplicitReferenceInputDto {
    private String kind;
    private String localId;
    private String sourceType;
    private UUID sourceId;
    private UUID targetStageId;
    private UUID targetChapterId;
    private UUID targetTurnId;
    private String snapshotText;

    @Builder.Default private String sourceRevision = "1";

    @Builder.Default private String authority = "EXPLICIT_REFERENCE";
}

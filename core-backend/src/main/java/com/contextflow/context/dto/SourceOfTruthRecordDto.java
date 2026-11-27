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
public class SourceOfTruthRecordDto {
    private UUID decisionId;
    private String title;
    private String scopeDescription;
    private String decisionContent;
    private String rationale;

    @Builder.Default private String status = "ACTIVE";

    private UUID supersedesDecisionId;

    @Builder.Default private String sourceRevision = "1";

    private Instant confirmedAt;

    @Builder.Default private String linkStatus = "AVAILABLE";
}

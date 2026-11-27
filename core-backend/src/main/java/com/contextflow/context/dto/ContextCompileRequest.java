package com.contextflow.context.dto;

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
public class ContextCompileRequest {
    private UUID projectId;
    private UUID conversationId;
    private UUID mainStageId;
    private UUID subBranchId;
    private String platformPolicy;

    @Builder.Default private List<ContentBlockDto> currentUserContentBlocks = new ArrayList<>();

    @Builder.Default private List<ExplicitReferenceInputDto> explicitReferences = new ArrayList<>();

    @Builder.Default private IntentContextDto intentContext = new IntentContextDto();
}

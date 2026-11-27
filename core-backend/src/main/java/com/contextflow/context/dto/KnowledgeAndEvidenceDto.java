package com.contextflow.context.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeAndEvidenceDto {
    @Builder.Default private List<ContextSourceItemDto> history = new ArrayList<>();

    @Builder.Default
    @JsonProperty("knowledge_base")
    private List<ContextSourceItemDto> knowledgeBase = new ArrayList<>();

    @Builder.Default
    @JsonProperty("source_of_truth")
    private List<ContextSourceItemDto> sourceOfTruth = new ArrayList<>();

    @Builder.Default
    @JsonProperty("explicit_references")
    private List<ExplicitReferenceItemDto> explicitReferences = new ArrayList<>();
}

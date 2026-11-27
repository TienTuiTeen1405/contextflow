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
public class ResponseInstructionsDto {
    @Builder.Default
    @JsonProperty("intent_context")
    private IntentContextDto intentContext = new IntentContextDto();

    @Builder.Default
    @JsonProperty("evidence_sufficiency")
    private EvidenceSufficiency evidenceSufficiency = EvidenceSufficiency.INSUFFICIENT;

    @Builder.Default
    @JsonProperty("retrieval_status")
    private RetrievalStatus retrievalStatus = RetrievalStatus.COMPLETE;

    @Builder.Default
    @JsonProperty("degraded_components")
    private List<DegradedComponentDto> degradedComponents = new ArrayList<>();
}

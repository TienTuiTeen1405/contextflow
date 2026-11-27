package com.contextflow.context.dto;

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
public class KnowledgeRetrievalResult {
    @Builder.Default private List<KnowledgeCandidateDto> items = new ArrayList<>();

    @Builder.Default private RetrievalStatus retrievalStatus = RetrievalStatus.COMPLETE;

    @Builder.Default
    private EvidenceSufficiency evidenceSufficiency = EvidenceSufficiency.INSUFFICIENT;

    @Builder.Default private List<DegradedComponentDto> degradedComponents = new ArrayList<>();

    public static KnowledgeRetrievalResult empty() {
        return KnowledgeRetrievalResult.builder().build();
    }

    public static KnowledgeRetrievalResult unavailable(String reasonCode) {
        return KnowledgeRetrievalResult.builder()
                .retrievalStatus(RetrievalStatus.UNAVAILABLE)
                .evidenceSufficiency(EvidenceSufficiency.INSUFFICIENT)
                .degradedComponents(
                        new ArrayList<>(
                                List.of(
                                        DegradedComponentDto.builder()
                                                .component(DegradedComponentType.VECTOR_SEARCH)
                                                .status(DegradedComponentStatus.UNAVAILABLE)
                                                .reasonCode(reasonCode)
                                                .build(),
                                        DegradedComponentDto.builder()
                                                .component(DegradedComponentType.KEYWORD_SEARCH)
                                                .status(DegradedComponentStatus.UNAVAILABLE)
                                                .reasonCode(reasonCode)
                                                .build())))
                .build();
    }
}

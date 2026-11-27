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
public class SourceOfTruthRequest {
    private UUID projectId;
    private String queryText;

    @Builder.Default private String timeScope = "CURRENT";

    @Builder.Default private List<String> resolvedEntities = new ArrayList<>();
}

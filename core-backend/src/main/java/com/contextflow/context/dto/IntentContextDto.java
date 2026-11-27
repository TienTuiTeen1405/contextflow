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
public class IntentContextDto {
    @Builder.Default
    @JsonProperty("primary_intent")
    private String primaryIntent = "GENERAL";

    @Builder.Default
    @JsonProperty("time_scope")
    private String timeScope = "CURRENT";

    @Builder.Default
    @JsonProperty("resolved_entities")
    private List<String> resolvedEntities = new ArrayList<>();

    @Builder.Default
    @JsonProperty("exact_lookup")
    private boolean exactLookup = false;
}

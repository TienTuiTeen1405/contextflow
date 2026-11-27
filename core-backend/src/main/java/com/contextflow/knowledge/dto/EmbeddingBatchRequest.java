package com.contextflow.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
public class EmbeddingBatchRequest {
    @JsonProperty("correlation_id")
    private UUID correlationId;

    @JsonProperty("provider_call_id")
    private UUID providerCallId;

    @JsonProperty("task_type")
    private String taskType;

    private String model;

    @JsonProperty("output_dimensionality")
    private int outputDimensionality;

    @Builder.Default private List<String> texts = new ArrayList<>();
}

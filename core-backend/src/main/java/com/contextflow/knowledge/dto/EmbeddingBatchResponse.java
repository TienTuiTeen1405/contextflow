package com.contextflow.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingBatchResponse {
    @JsonProperty("correlation_id")
    private UUID correlationId;

    @JsonProperty("provider_call_id")
    private UUID providerCallId;

    private boolean dispatched;

    @JsonProperty("occurred_at")
    private Instant occurredAt;

    @JsonProperty("completed_at")
    private Instant completedAt;

    private String model;

    @JsonProperty("provider_request_id")
    private String providerRequestId;

    @Builder.Default private List<List<Double>> vectors = new ArrayList<>();

    @JsonProperty("usage_status")
    private String usageStatus;
}

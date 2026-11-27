package com.contextflow.context.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplicitReferenceItemDto {
    private String kind;
    private String id;

    @JsonProperty("source_type")
    private String sourceType;

    @JsonProperty("snapshot_text")
    private String snapshotText;

    @JsonProperty("source_revision")
    private String sourceRevision;

    private String authority;
}

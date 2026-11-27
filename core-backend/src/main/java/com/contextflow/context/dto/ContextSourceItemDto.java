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
public class ContextSourceItemDto {
    @JsonProperty("source_ref")
    private String sourceRef;

    @JsonProperty("source_type")
    private String sourceType;

    private String content;
    private String authority;

    @JsonProperty("source_revision")
    private String sourceRevision;

    @JsonProperty("source_link_status")
    private String sourceLinkStatus;
}

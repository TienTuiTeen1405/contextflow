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
public class DegradedComponentDto {
    private DegradedComponentType component;
    private DegradedComponentStatus status;

    @JsonProperty("reason_code")
    private String reasonCode;
}

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
public class IntelligenceBackgroundDto {
    @JsonProperty("project_information")
    private String projectInformation;

    @JsonProperty("personal_profile")
    private String personalProfile;
}

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
public class IntelligenceInstructionsDto {
    @Builder.Default
    @JsonProperty("mandatory_rules")
    private List<InstructionItemDto> mandatoryRules = new ArrayList<>();

    @Builder.Default
    @JsonProperty("overridable_rules")
    private List<InstructionItemDto> overridableRules = new ArrayList<>();

    @Builder.Default
    @JsonProperty("active_skills")
    private List<InstructionItemDto> activeSkills = new ArrayList<>();

    @JsonProperty("personal_instructions")
    private String personalInstructions;
}

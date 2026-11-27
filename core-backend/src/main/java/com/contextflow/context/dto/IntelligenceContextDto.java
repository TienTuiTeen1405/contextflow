package com.contextflow.context.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntelligenceContextDto {
    private IntelligenceBackgroundDto background;
    private IntelligenceInstructionsDto instructions;
}

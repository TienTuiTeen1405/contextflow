package com.contextflow.knowledge.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveIndexGenerationDto {
    private UUID id;
    private UUID projectId;
    private String embeddingModel;
    private int outputDimensionality;
}

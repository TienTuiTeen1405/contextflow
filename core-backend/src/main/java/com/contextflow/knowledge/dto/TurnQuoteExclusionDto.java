package com.contextflow.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnQuoteExclusionDto {
    private String quoteRefId;
    private String snapshotText;
    private boolean excludeFromAutoKb;
}

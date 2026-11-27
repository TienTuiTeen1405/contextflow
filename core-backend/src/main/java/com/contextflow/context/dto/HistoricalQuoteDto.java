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
public class HistoricalQuoteDto {
    @JsonProperty("quote_ref_id")
    private String quoteRefId;

    @JsonProperty("snapshot_text")
    private String snapshotText;

    @JsonProperty("source_type")
    private String sourceType;
}

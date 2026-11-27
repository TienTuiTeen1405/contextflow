package com.contextflow.context.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContinuityTurnDto {
    @JsonProperty("turn_ref")
    private String turnRef;

    @Builder.Default
    @JsonProperty("user_text_blocks")
    private List<String> userTextBlocks = new ArrayList<>();

    @Builder.Default
    @JsonProperty("historical_quotes")
    private List<HistoricalQuoteDto> historicalQuotes = new ArrayList<>();

    @Builder.Default
    @JsonProperty("native_output_steps")
    private List<Map<String, Object>> nativeOutputSteps = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("user_content_blocks")
    private List<ContentBlockDto> userContentBlocks;
}

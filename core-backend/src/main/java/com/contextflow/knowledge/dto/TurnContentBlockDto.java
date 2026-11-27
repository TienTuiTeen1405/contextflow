package com.contextflow.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnContentBlockDto {
    private String type;
    private String text;
    private String quoteRefId;

    public static TurnContentBlockDto textBlock(String text) {
        return TurnContentBlockDto.builder().type("TEXT").text(text).build();
    }

    public static TurnContentBlockDto quoteRefBlock(String quoteRefId) {
        return TurnContentBlockDto.builder().type("QUOTE_REF").quoteRefId(quoteRefId).build();
    }
}

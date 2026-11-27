package com.contextflow.context.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentBlockDto {
    private String type;
    private String text;

    @JsonProperty("quote_ref_id")
    private String quoteRefId;

    public static ContentBlockDto textBlock(String text) {
        return ContentBlockDto.builder().type("TEXT").text(text).build();
    }

    public static ContentBlockDto quoteRefBlock(String quoteRefId) {
        return ContentBlockDto.builder().type("QUOTE_REF").quoteRefId(quoteRefId).build();
    }
}

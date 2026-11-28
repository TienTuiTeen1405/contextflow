package com.contextflow.conversation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A TEXT block carries only {@code text}; a QUOTE_REF block carries only {@code quoteRefId}. Null
 * fields are omitted because the contract forbids additional properties on each variant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentBlockResponse {
    private ContentBlockType type;
    private String text;
    private String quoteRefId;

    public static ContentBlockResponse text(String text) {
        return ContentBlockResponse.builder().type(ContentBlockType.TEXT).text(text).build();
    }

    public static ContentBlockResponse quoteRef(String quoteRefId) {
        return ContentBlockResponse.builder()
                .type(ContentBlockType.QUOTE_REF)
                .quoteRefId(quoteRefId)
                .build();
    }
}

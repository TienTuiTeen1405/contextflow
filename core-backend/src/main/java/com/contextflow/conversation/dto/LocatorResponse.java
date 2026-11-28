package com.contextflow.conversation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Position of a quote inside its source (contract schema {@code Locator}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocatorResponse {
    private Long charStart;
    private Long charEnd;
    private String prefixAnchor;
    private String suffixAnchor;
}

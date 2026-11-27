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
public class CurrentUserRequestDto {
    @Builder.Default
    @JsonProperty("content_blocks")
    private List<ContentBlockDto> contentBlocks = new ArrayList<>();
}

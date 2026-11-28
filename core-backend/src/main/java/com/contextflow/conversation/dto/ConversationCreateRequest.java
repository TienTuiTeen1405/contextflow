package com.contextflow.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationCreateRequest {
    @NotBlank
    @Schema(example = "Hội thoại đầu tiên", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;
}

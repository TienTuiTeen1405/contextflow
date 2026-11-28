package com.contextflow.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Renames a conversation. {@code title} is the only field supported in M1, so it is required;
 * archive/status changes are outside PR-4A and are rejected as unknown fields.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationPatchRequest {
    @NotBlank
    @Schema(example = "Tiêu đề mới", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;
}

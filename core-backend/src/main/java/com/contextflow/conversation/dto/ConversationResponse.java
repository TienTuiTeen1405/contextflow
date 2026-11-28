package com.contextflow.conversation.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationResponse {
    private UUID id;
    private UUID projectId;
    private String title;
    private ConversationStatus status;

    /** Current Main Stage (highest {@code stage_order}); {@code null} when none exists. */
    private UUID mainStageId;

    private Instant createdAt;
    private Instant updatedAt;
}

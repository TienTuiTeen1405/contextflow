package com.contextflow.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Committed Turn (contract schema {@code Turn}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnResponse {
    private UUID id;
    private UUID projectId;
    private UUID conversationId;
    private UUID mainStageId;
    private UUID subBranchId;
    private UUID chapterId;
    private long sequenceNo;
    private long sourceRevision;
    private List<ContentBlockResponse> userContentBlocks;
    private List<QuoteSavedResponse> quotes;
    private List<MainReferenceResponse> mainReferences;
    private String assistantText;
    private Instant createdAt;
}

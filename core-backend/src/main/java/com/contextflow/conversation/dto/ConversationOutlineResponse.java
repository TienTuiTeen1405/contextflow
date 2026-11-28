package com.contextflow.conversation.dto;

import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationOutlineResponse {
    private UUID conversationId;

    /** Main Stages ordered by {@code stage_order} ascending. */
    private List<StageOutlineItem> stages;

    /** Always empty in M1. */
    private List<ChapterOutlineItem> chapters;

    /** Always empty in M1. */
    private List<SubBranchOutlineItem> subBranches;
}

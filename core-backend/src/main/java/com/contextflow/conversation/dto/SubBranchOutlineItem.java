package com.contextflow.conversation.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sub-Branch entry of the outline. Sub-Branches are outside M1, so the outline returns an empty
 * list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubBranchOutlineItem {
    private UUID id;
    private UUID mainStageId;
    private UUID anchorTurnId;
    private String title;
}

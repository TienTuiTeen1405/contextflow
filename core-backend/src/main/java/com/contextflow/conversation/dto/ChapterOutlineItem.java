package com.contextflow.conversation.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Chapter entry of the outline. Chapters are outside M1, so the outline returns an empty list. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterOutlineItem {
    private UUID id;
    private UUID mainStageId;
    private String title;
    private int displayOrder;
}

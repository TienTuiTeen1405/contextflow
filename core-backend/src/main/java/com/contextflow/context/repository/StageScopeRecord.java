package com.contextflow.context.repository;

import java.util.ArrayList;
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
public class StageScopeRecord {
    private UUID id;
    private UUID projectId;
    private UUID conversationId;
    private UUID parentStageId;

    @Builder.Default private List<UUID> ancestorStageIds = new ArrayList<>();

    private int stageOrder;
    private String title;
    private String stageStatus;
    private String conversationStatus;
    private UUID anchorTurnId;
}

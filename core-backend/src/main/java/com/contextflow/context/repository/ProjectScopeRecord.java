package com.contextflow.context.repository;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectScopeRecord {
    private UUID id;
    private UUID ownerUserId;
    private String projectInformation;
    private boolean projectIntelligenceEnabled;
    private String status;
}

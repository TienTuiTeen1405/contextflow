package com.contextflow.context.repository;

import com.contextflow.context.dto.RuleEnforcementLevel;
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
public class RuleRecord {
    private UUID id;
    private String name;
    private String content;
    private RuleEnforcementLevel enforcementLevel;
    private int priority;
    private Instant createdAt;
}

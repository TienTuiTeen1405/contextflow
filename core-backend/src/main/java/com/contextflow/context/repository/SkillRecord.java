package com.contextflow.context.repository;

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
public class SkillRecord {
    private UUID id;
    private String name;
    private String description;
    private String content;
    private int priority;
    private Instant createdAt;
}

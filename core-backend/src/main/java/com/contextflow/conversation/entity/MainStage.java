package com.contextflow.conversation.entity;

import com.contextflow.conversation.dto.MainStageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Main Stage of a Conversation. {@code ancestor_stage_ids} and {@code previous_status} belong to
 * the Macro-Fork and deletion flows; they stay unmapped so inserts keep the DB defaults.
 */
@Entity
@Table(name = "main_stages", schema = "contextflow")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MainStage {
    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID projectId;

    @Column(nullable = false, updatable = false)
    private UUID conversationId;

    private UUID parentStageId;

    @Column(nullable = false)
    private int stageOrder;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MainStageStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}

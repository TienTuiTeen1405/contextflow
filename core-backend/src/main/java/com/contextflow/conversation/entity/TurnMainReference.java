package com.contextflow.conversation.entity;

import com.contextflow.conversation.dto.ReferenceGranularity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Read-only mapping of an {@code @Main} reference saved with a committed Turn. */
@Entity
@Immutable
@Table(name = "turn_main_references", schema = "contextflow")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TurnMainReference {
    @Id private UUID id;

    @Column(nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private UUID conversationId;

    @Column(nullable = false)
    private UUID turnId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReferenceGranularity granularity;

    @Column(nullable = false)
    private UUID targetStageId;

    private UUID targetChapterId;

    private UUID targetTurnId;

    @Column(nullable = false)
    private Instant createdAt;
}

package com.contextflow.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read-only mapping of a committed Turn. Turns are written by the Chat Worker; this domain only
 * reads them, so the entity is {@link Immutable}.
 */
@Entity
@Immutable
@Table(name = "turns", schema = "contextflow")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Turn {
    @Id private UUID id;

    @Column(nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private UUID conversationId;

    @Column(nullable = false)
    private UUID mainStageId;

    private UUID subBranchId;

    private UUID chapterId;

    @Column(nullable = false)
    private UUID requestId;

    @Column(nullable = false)
    private long sequenceNo;

    @Column(nullable = false)
    private long sourceRevision;

    /** Raw {@code jsonb} array; parsed into typed content blocks by the mapper. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String userContentBlocks;

    @Column(nullable = false)
    private String assistantText;

    @Column(nullable = false)
    private Instant createdAt;
}

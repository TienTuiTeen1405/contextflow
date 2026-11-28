package com.contextflow.conversation.entity;

import com.contextflow.conversation.dto.LinkStatus;
import com.contextflow.conversation.dto.QuoteSourceKind;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Read-only mapping of a quote saved with a committed Turn. */
@Entity
@Immutable
@Table(name = "turn_quote_references", schema = "contextflow")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TurnQuoteReference {
    @Id private UUID id;

    @Column(nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private UUID conversationId;

    @Column(nullable = false)
    private UUID turnId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuoteSourceKind sourceKind;

    @Column(nullable = false)
    private UUID sourceId;

    @Column(nullable = false)
    private String sourceRevision;

    /** Raw {@code jsonb} object; parsed into a typed locator by the mapper. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String locator;

    @Column(nullable = false)
    private byte[] contentHash;

    @Column(nullable = false)
    private String snapshotText;

    @Column(nullable = false)
    private boolean excludeFromAutoKb;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LinkStatus linkStatus;

    @Column(nullable = false)
    private Instant createdAt;
}

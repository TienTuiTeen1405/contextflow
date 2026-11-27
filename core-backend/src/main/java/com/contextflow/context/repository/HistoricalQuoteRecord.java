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
public class HistoricalQuoteRecord {
    private UUID id;
    private UUID turnId;
    private String sourceKind;
    private String snapshotText;
    private Instant createdAt;
}

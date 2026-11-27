package com.contextflow.context.repository;

import com.contextflow.context.dto.ContentBlockDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveWindowTurnRecord {
    private UUID turnId;
    private long sequenceNo;
    private long sourceRevision;
    private long ordinal;

    @Builder.Default private List<ContentBlockDto> rawUserContentBlocks = new ArrayList<>();

    @Builder.Default private List<HistoricalQuoteRecord> historicalQuotes = new ArrayList<>();

    @Builder.Default private List<Map<String, Object>> nativeOutputSteps = new ArrayList<>();

    private Instant createdAt;
}

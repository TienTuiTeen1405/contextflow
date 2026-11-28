package com.contextflow.conversation.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Quote saved with a committed Turn (contract schema {@code QuoteSaved}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteSavedResponse {
    private UUID quoteRefId;
    private QuoteSourceKind sourceKind;
    private UUID sourceId;
    private String sourceRevision;
    private LocatorResponse locator;
    private String contentHashSha256Hex;
    private String snapshotText;
    private LinkStatus linkStatus;
    private boolean excludeFromAutoKb;
}

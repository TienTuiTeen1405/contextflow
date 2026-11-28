package com.contextflow.conversation.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Page of committed Turns (contract schema {@code Turns}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnsPageResponse {
    /** Turns ordered by {@code sequenceNo} ascending. */
    private List<TurnResponse> items;

    /**
     * {@code sequenceNo} of the last returned Turn, to pass as {@code afterSequence} for newer
     * Turns. When the page is empty it repeats the requested {@code afterSequence}, or is {@code
     * null} for a {@code beforeSequence} request.
     */
    private Long nextAfterSequence;

    /**
     * Whether more Turns exist in the paging direction: newer ones for {@code afterSequence} (or no
     * cursor), older ones for {@code beforeSequence}.
     */
    private boolean hasMore;
}

package com.contextflow.knowledge.chunking;

import com.contextflow.knowledge.dto.TurnContentBlockDto;
import com.contextflow.knowledge.dto.TurnIngestionRequest;
import com.contextflow.knowledge.dto.TurnQuoteExclusionDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Applies Pre-Chunking Exclusion for Auto-KB projection: drops any Quote block marked
 * exclude_from_auto_kb = true (e.g., quotes sourced from unmerged Sub-Branches) before chunking.
 */
@Component
public class PreChunkingFilter {

    public FilteredTurnContent filter(TurnIngestionRequest request) {
        Map<String, TurnQuoteExclusionDto> quotesByRefId = new HashMap<>();
        if (request.getQuoteSnapshots() != null) {
            for (TurnQuoteExclusionDto quote : request.getQuoteSnapshots()) {
                if (quote != null && quote.getQuoteRefId() != null) {
                    quotesByRefId.put(quote.getQuoteRefId(), quote);
                }
            }
        }

        StringBuilder userBuilder = new StringBuilder();
        int excludedCount = 0;
        List<TurnContentBlockDto> blocks = request.getUserContentBlocks();
        if (blocks != null) {
            for (TurnContentBlockDto block : blocks) {
                if (block == null) {
                    continue;
                }
                if ("TEXT".equals(block.getType()) && block.getText() != null) {
                    appendSegment(userBuilder, block.getText());
                } else if ("QUOTE_REF".equals(block.getType()) && block.getQuoteRefId() != null) {
                    TurnQuoteExclusionDto quote = quotesByRefId.get(block.getQuoteRefId());
                    if (quote == null) {
                        continue;
                    }
                    if (quote.isExcludeFromAutoKb()) {
                        excludedCount++;
                        continue;
                    }
                    if (quote.getSnapshotText() != null && !quote.getSnapshotText().isBlank()) {
                        appendSegment(userBuilder, quote.getSnapshotText());
                    }
                }
            }
        }

        String userText = userBuilder.toString().trim();
        String assistantText =
                request.getAssistantText() == null ? "" : request.getAssistantText().trim();

        StringBuilder combined = new StringBuilder();
        if (!userText.isEmpty()) {
            combined.append("User: ").append(userText);
        }
        if (!assistantText.isEmpty()) {
            if (!combined.isEmpty()) {
                combined.append("\n\n");
            }
            combined.append("Assistant: ").append(assistantText);
        }

        return FilteredTurnContent.builder()
                .projectedContent(combined.toString().trim())
                .excludedQuoteCount(excludedCount)
                .build();
    }

    private static void appendSegment(StringBuilder sb, String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(trimmed);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilteredTurnContent {
        private String projectedContent;
        private int excludedQuoteCount;
    }
}

package com.contextflow.knowledge.chunking;

import com.contextflow.knowledge.dto.KnowledgeChunkDraft;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic text chunker targeting 500-800 tokens with overlap while preserving fenced code
 * blocks and Vietnamese/prose paragraph boundaries, plus extracting technical_terms for Exact
 * Lookup.
 */
@Component
public class KnowledgeChunker {
    public static final int MIN_TARGET_TOKENS = 500;
    public static final int TARGET_CHUNK_TOKENS = 650;
    public static final int MAX_CHUNK_TOKENS = 800;
    public static final int OVERLAP_TOKENS = 80;
    private static final int CHARS_PER_TOKEN = 4;
    private static final int MAX_TECHNICAL_TERMS = 64;

    private static final Pattern CODE_ID_PATTERN =
            Pattern.compile("\\b[A-Z]{2,}(?:-[A-Z0-9]+)+\\b");
    private static final Pattern SNAKE_CASE_PATTERN =
            Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");
    private static final Pattern CAMEL_CASE_PATTERN =
            Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\\b");
    private static final Pattern BACKTICK_TERM_PATTERN = Pattern.compile("`([^`\\r\\n]{2,64})`");
    private static final Pattern API_PATH_PATTERN =
            Pattern.compile("(?<![\\w/])/[a-zA-Z0-9._-]+(?:/[a-zA-Z0-9._:{}-]+)+");

    public List<KnowledgeChunkDraft> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").trim();
        if (estimateTokens(normalized) <= MAX_CHUNK_TOKENS) {
            return List.of(toDraft(0, normalized));
        }

        List<String> units = splitPreservingCodeFences(normalized);
        List<KnowledgeChunkDraft> drafts = new ArrayList<>();
        List<String> currentUnits = new ArrayList<>();
        int currentTokens = 0;

        for (String unit : units) {
            int unitTokens = estimateTokens(unit);
            if (unitTokens > MAX_CHUNK_TOKENS) {
                if (!currentUnits.isEmpty()) {
                    drafts.add(toDraft(drafts.size(), String.join("\n\n", currentUnits)));
                    currentUnits = buildOverlapTail(currentUnits);
                    currentTokens = estimateTokens(String.join("\n\n", currentUnits));
                }
                for (String slice : sliceOversizedUnit(unit)) {
                    drafts.add(toDraft(drafts.size(), slice));
                }
                currentUnits.clear();
                currentTokens = 0;
                continue;
            }

            if (!currentUnits.isEmpty() && currentTokens + unitTokens > MAX_CHUNK_TOKENS) {
                drafts.add(toDraft(drafts.size(), String.join("\n\n", currentUnits)));
                currentUnits = buildOverlapTail(currentUnits);
                currentTokens = estimateTokens(String.join("\n\n", currentUnits));
            }
            currentUnits.add(unit);
            currentTokens += unitTokens;
        }

        if (!currentUnits.isEmpty()) {
            String tail = String.join("\n\n", currentUnits).trim();
            if (!tail.isEmpty()) {
                drafts.add(toDraft(drafts.size(), tail));
            }
        }
        return drafts;
    }

    public List<String> extractTechnicalTerms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        addMatches(CODE_ID_PATTERN, text, terms, 0);
        addMatches(SNAKE_CASE_PATTERN, text, terms, 0);
        addMatches(CAMEL_CASE_PATTERN, text, terms, 0);
        addMatches(API_PATH_PATTERN, text, terms, 0);
        addMatches(BACKTICK_TERM_PATTERN, text, terms, 1);

        List<String> result = new ArrayList<>(Math.min(terms.size(), MAX_TECHNICAL_TERMS));
        for (String term : terms) {
            if (result.size() >= MAX_TECHNICAL_TERMS) {
                break;
            }
            result.add(term);
        }
        return result;
    }

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / (double) CHARS_PER_TOKEN));
    }

    private KnowledgeChunkDraft toDraft(int chunkIndex, String content) {
        String trimmed = content.trim();
        return KnowledgeChunkDraft.builder()
                .chunkIndex(chunkIndex)
                .content(trimmed)
                .searchText(trimmed)
                .technicalTerms(extractTechnicalTerms(trimmed))
                .estimatedTokens(estimateTokens(trimmed))
                .build();
    }

    private List<String> splitPreservingCodeFences(String text) {
        List<String> units = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        StringBuilder buffer = new StringBuilder();
        boolean inCodeFence = false;

        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.startsWith("```")) {
                if (!inCodeFence) {
                    flushUnit(units, buffer);
                    inCodeFence = true;
                    buffer.append(line);
                } else {
                    buffer.append('\n').append(line);
                    inCodeFence = false;
                    flushUnit(units, buffer);
                }
                continue;
            }

            if (!inCodeFence && trimmedLine.isEmpty()) {
                flushUnit(units, buffer);
                continue;
            }

            if (!buffer.isEmpty()) {
                buffer.append('\n');
            }
            buffer.append(line);
        }
        flushUnit(units, buffer);
        return units;
    }

    private static void flushUnit(List<String> units, StringBuilder buffer) {
        String content = buffer.toString().trim();
        if (!content.isEmpty()) {
            units.add(content);
        }
        buffer.setLength(0);
    }

    private List<String> buildOverlapTail(List<String> units) {
        List<String> overlap = new ArrayList<>();
        int tokens = 0;
        for (int i = units.size() - 1; i >= 0; i--) {
            String unit = units.get(i);
            int unitTokens = estimateTokens(unit);
            if (!overlap.isEmpty() && tokens + unitTokens > OVERLAP_TOKENS) {
                break;
            }
            overlap.add(0, unit);
            tokens += unitTokens;
            if (tokens >= OVERLAP_TOKENS) {
                break;
            }
        }
        return overlap;
    }

    private List<String> sliceOversizedUnit(String unit) {
        int maxChars = TARGET_CHUNK_TOKENS * CHARS_PER_TOKEN;
        int overlapChars = OVERLAP_TOKENS * CHARS_PER_TOKEN;
        List<String> slices = new ArrayList<>();
        int start = 0;
        int len = unit.length();
        while (start < len) {
            int end = Math.min(len, start + maxChars);
            if (end < len) {
                int splitAt = findWordBoundary(unit, start, end);
                if (splitAt > start) {
                    end = splitAt;
                }
            }
            String piece = unit.substring(start, end).trim();
            if (!piece.isEmpty()) {
                slices.add(piece);
            }
            if (end >= len) {
                break;
            }
            start = Math.max(end - overlapChars, start + 1);
        }
        return slices;
    }

    private static int findWordBoundary(String text, int start, int end) {
        int newline = text.lastIndexOf('\n', end);
        if (newline > start + (end - start) / 2) {
            return newline;
        }
        int space = text.lastIndexOf(' ', end);
        if (space > start + (end - start) / 2) {
            return space;
        }
        return end;
    }

    private static void addMatches(Pattern pattern, String text, Set<String> out, int group) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(group).trim();
            if (candidate.length() >= 2 && candidate.length() <= 80) {
                out.add(candidate);
            }
        }
    }
}

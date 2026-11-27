package com.contextflow.retrieval.intent;

import com.contextflow.context.dto.IntentContextDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Pure-backend deterministic query intent resolver (zero LLM calls). Classifies primary_intent,
 * time_scope (CURRENT vs HISTORICAL_OR_COMPARE), exact_lookup, and resolved_entities.
 */
@Component
public class IntentResolver {
    private static final Pattern CODE_ID_PATTERN =
            Pattern.compile("\\b[A-Z]{2,}(?:-[A-Z0-9]+)+\\b");
    private static final Pattern SNAKE_CASE_PATTERN =
            Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");
    private static final Pattern CAMEL_CASE_PATTERN =
            Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\\b");
    private static final Pattern BACKTICK_TERM_PATTERN = Pattern.compile("`([^`\\r\\n]{2,64})`");
    private static final Pattern API_PATH_PATTERN =
            Pattern.compile("(?<![\\w/])/[a-zA-Z0-9._-]+(?:/[a-zA-Z0-9._:{}-]+)+");

    private static final List<String> COMPARE_MARKERS =
            List.of("so sánh", "khác nhau", "compare", "diff", "versus", " vs ");
    private static final List<String> HISTORICAL_MARKERS =
            List.of(
                    "trước đây",
                    "lịch sử",
                    "phiên bản cũ",
                    "ban đầu",
                    "before",
                    "previously",
                    "history",
                    "earlier",
                    "old version");
    private static final List<String> EXPLAIN_MARKERS =
            List.of("giải thích", "tại sao", "vì sao", "như thế nào", "explain", "why", "how does");
    private static final List<String> TROUBLESHOOT_MARKERS =
            List.of("lỗi", "bug", "error", "fail", "crash", "exception", "timeout", "fix");
    private static final List<String> SUMMARIZE_MARKERS =
            List.of("tóm tắt", "tổng hợp", "summarize", "summary", "recap");

    public IntentContextDto resolve(String queryText, IntentContextDto supplied) {
        String text = queryText == null ? "" : queryText.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        Set<String> entities = new LinkedHashSet<>();
        if (supplied != null && supplied.getResolvedEntities() != null) {
            entities.addAll(supplied.getResolvedEntities());
        }
        extractEntities(text, entities);

        boolean exactLookup = (supplied != null && supplied.isExactLookup()) || !entities.isEmpty();

        String timeScope;
        if (supplied != null
                && supplied.getTimeScope() != null
                && !"CURRENT".equals(supplied.getTimeScope())) {
            timeScope = supplied.getTimeScope();
        } else if (containsAny(lower, COMPARE_MARKERS) || containsAny(lower, HISTORICAL_MARKERS)) {
            timeScope = "HISTORICAL_OR_COMPARE";
        } else {
            timeScope = "CURRENT";
        }

        String primaryIntent;
        if (supplied != null
                && supplied.getPrimaryIntent() != null
                && !"GENERAL".equals(supplied.getPrimaryIntent())) {
            primaryIntent = supplied.getPrimaryIntent();
        } else if (containsAny(lower, COMPARE_MARKERS)) {
            primaryIntent = "COMPARE";
        } else if (containsAny(lower, TROUBLESHOOT_MARKERS)) {
            primaryIntent = "TROUBLESHOOT";
        } else if (containsAny(lower, SUMMARIZE_MARKERS)) {
            primaryIntent = "SUMMARIZE";
        } else if (containsAny(lower, EXPLAIN_MARKERS)) {
            primaryIntent = "EXPLAIN";
        } else if (exactLookup) {
            primaryIntent = "LOOKUP";
        } else {
            primaryIntent = "GENERAL";
        }

        return IntentContextDto.builder()
                .primaryIntent(primaryIntent)
                .timeScope(timeScope)
                .resolvedEntities(new ArrayList<>(entities))
                .exactLookup(exactLookup)
                .build();
    }

    private static void extractEntities(String text, Set<String> out) {
        if (text.isEmpty()) {
            return;
        }
        addMatches(CODE_ID_PATTERN, text, out, 0);
        addMatches(SNAKE_CASE_PATTERN, text, out, 0);
        addMatches(CAMEL_CASE_PATTERN, text, out, 0);
        addMatches(API_PATH_PATTERN, text, out, 0);
        addMatches(BACKTICK_TERM_PATTERN, text, out, 1);
    }

    private static void addMatches(Pattern pattern, String text, Set<String> out, int group) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String candidate = m.group(group).trim();
            if (candidate.length() >= 2 && candidate.length() <= 80) {
                out.add(candidate);
            }
        }
    }

    private static boolean containsAny(String lower, List<String> markers) {
        for (String marker : markers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}

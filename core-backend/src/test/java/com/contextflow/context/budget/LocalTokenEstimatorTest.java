package com.contextflow.context.budget;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LocalTokenEstimatorTest {
    private final LocalTokenEstimator estimator = new LocalTokenEstimator(new ObjectMapper());

    @Test
    void appliesExactOnePointOneFiveSafetyMultiplier() {
        String fourThousandAscii = "a".repeat(4_000);
        int raw = estimator.estimateRawTokens(fourThousandAscii);
        int safe = estimator.estimateTokens(fourThousandAscii);

        assertEquals(1_000, raw);
        assertEquals(1_150, safe);
        assertEquals(0, estimator.estimateTokens(""));
        assertEquals(0, estimator.estimateTokens(null));
    }

    @Test
    void scalesConservativelyForVietnameseUtf8Text() {
        String vietnamese = "Quyết định kiến trúc PostgreSQL 16 với pgvector. ";
        int raw = estimator.estimateRawTokens(vietnamese);
        int safe = estimator.estimateTokens(vietnamese);

        assertTrue(raw > 0);
        assertEquals((int) Math.ceil(raw * 1.15), safe);
    }
}

package com.contextflow.knowledge.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Thread-safe Token-Bucket TPM (tokens-per-minute) rate limiter to smooth embedding_outbox batch
 * dispatches and avoid bursts of upstream HTTP 429 errors.
 */
public class TokenBucketTpmLimiter {
    private final int capacityTokens;
    private final double refillTokensPerMillis;
    private final Clock clock;

    private double availableTokens;
    private Instant lastRefillAt;

    public TokenBucketTpmLimiter(int tokensPerMinute, Clock clock) {
        if (tokensPerMinute <= 0) {
            throw new IllegalArgumentException("tokensPerMinute must be positive");
        }
        this.capacityTokens = tokensPerMinute;
        this.refillTokensPerMillis = tokensPerMinute / 60_000.0;
        this.clock = clock;
        this.availableTokens = tokensPerMinute;
        this.lastRefillAt = clock.instant();
    }

    public synchronized boolean tryConsume(int requestedTokens) {
        refill();
        int cost = Math.max(1, Math.min(requestedTokens, capacityTokens));
        if (availableTokens >= cost) {
            availableTokens -= cost;
            return true;
        }
        return false;
    }

    public synchronized Duration estimateWaitDuration(int requestedTokens) {
        refill();
        int cost = Math.max(1, Math.min(requestedTokens, capacityTokens));
        if (availableTokens >= cost) {
            return Duration.ZERO;
        }
        double deficit = cost - availableTokens;
        long millis = Math.max(100L, (long) Math.ceil(deficit / refillTokensPerMillis));
        return Duration.ofMillis(millis);
    }

    public synchronized double availableTokens() {
        refill();
        return availableTokens;
    }

    private void refill() {
        Instant now = clock.instant();
        long elapsedMillis = Duration.between(lastRefillAt, now).toMillis();
        if (elapsedMillis > 0) {
            double added = elapsedMillis * refillTokensPerMillis;
            availableTokens = Math.min(capacityTokens, availableTokens + added);
            lastRefillAt = now;
        }
    }
}

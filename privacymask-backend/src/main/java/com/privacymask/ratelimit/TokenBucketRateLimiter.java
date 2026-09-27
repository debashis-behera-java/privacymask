package com.privacymask.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token-bucket rate limiter, one bucket per authenticated client
 * identity (a SHA-256 digest of the API key — never the raw credential).
 *
 * <p>Algorithm: each bucket holds up to {@code capacity} tokens; every
 * admitted request consumes exactly one token at admission time (never
 * refunded on downstream provider failures, keeping abuse accounting
 * deterministic); tokens refill continuously at {@code refillPerMinute}.
 * When no whole token remains the request is denied with a conservative
 * {@code Retry-After} estimate.</p>
 *
 * <p>WebFlux compatibility: admission is pure CPU work (a few arithmetic
 * operations under a short per-bucket lock). No blocking I/O, no sleeping,
 * no network or database calls. Thread safety comes
 * from a {@link ConcurrentHashMap} of independently locked buckets, so
 * concurrent requests for different clients never contend and concurrent
 * requests for the same client serialize only for nanoseconds — token counts
 * can never go negative and bursts can never exceed capacity.</p>
 *
 * <p>Memory bound: at most {@value #MAX_TRACKED_CLIENTS} client entries are
 * retained; beyond that a best-effort eviction drops an arbitrary other
 * entry. The single-key Phase 12 deployment keeps exactly one entry.
 * State is never persisted and never leaves this process.</p>
 *
 * <p>Time flows through an injected {@link Clock} so tests can drive refill
 * deterministically without real sleeps.</p>
 */
public class TokenBucketRateLimiter {

    /**
     * Upper bound on tracked client identities. Far above the single-key
     * Phase 12 deployment; documents that the map cannot grow without limit.
     */
    static final int MAX_TRACKED_CLIENTS = 1000;

    private final double capacity;
    private final double refillPerSecond;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(RateLimitProperties properties, Clock clock) {
        if (properties.capacity() < 1 || properties.refillPerMinute() < 1) {
            throw new IllegalArgumentException("Rate-limit capacity and refill must be at least 1.");
        }
        this.capacity = properties.capacity();
        this.refillPerSecond = properties.refillPerMinute() / 60.0;
        this.clock = clock;
    }

    /**
     * Attempts to admit one request for the given client identity.
     * Consumes one token when admitted; consumes nothing when denied.
     */
    public RateLimitDecision tryAcquire(String clientIdentity) {
        Bucket bucket = buckets.computeIfAbsent(clientIdentity, key -> new Bucket(capacity, clock.instant()));
        boundSize(clientIdentity);
        return bucket.tryAcquire(capacity, refillPerSecond, clock.instant());
    }

    /** Number of client buckets currently tracked (observability for tests). */
    int trackedClients() {
        return buckets.size();
    }

    private void boundSize(String currentIdentity) {
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.keySet().stream()
                    .filter(key -> !key.equals(currentIdentity))
                    .findAny()
                    .ifPresent(buckets::remove);
        }
    }

    /**
     * A single client's token bucket. All state transitions happen under the
     * bucket's own monitor; each admission holds the lock only for a few
     * arithmetic operations (never waits, never blocks on I/O).
     */
    private static final class Bucket {
        private double tokens;
        private Instant lastRefill;

        Bucket(double capacity, Instant now) {
            this.tokens = capacity;
            this.lastRefill = now;
        }

        synchronized RateLimitDecision tryAcquire(double capacity, double refillPerSecond, Instant now) {
            if (now.isAfter(lastRefill)) {
                double elapsedSeconds = Duration.between(lastRefill, now).toNanos() / 1_000_000_000.0;
                tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
                lastRefill = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return RateLimitDecision.admit();
            }
            double deficit = 1.0 - tokens;
            long retryAfter = (long) Math.ceil(deficit / refillPerSecond);
            return RateLimitDecision.reject(retryAfter);
        }
    }
}

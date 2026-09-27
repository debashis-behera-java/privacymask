package com.privacymask.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic unit tests for the token-bucket limiter. Time flows through
 * a controllable {@link MutableClock}: refill is proven by advancing the
 * clock, never by real sleeps.
 */
class TokenBucketRateLimiterTests {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** Manually advanced clock: refill testing without wall-clock flakiness. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        @Override
        public ZoneId getZone() {
            return UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }
    }

    private static TokenBucketRateLimiter limiter(int capacity, int refillPerMinute, MutableClock clock) {
        return new TokenBucketRateLimiter(new RateLimitProperties(true, capacity, refillPerMinute), clock);
    }

    @Test
    void burstUpToCapacityIsAdmitted() {
        MutableClock clock = new MutableClock();
        TokenBucketRateLimiter limiter = limiter(3, 60, clock);

        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
    }

    @Test
    void requestOverCapacityIsDeniedWithRetryAfter() {
        MutableClock clock = new MutableClock();
        TokenBucketRateLimiter limiter = limiter(2, 60, clock);

        limiter.tryAcquire("client");
        limiter.tryAcquire("client");
        RateLimitDecision decision = limiter.tryAcquire("client");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isPositive();
    }

    @Test
    void deniedRequestConsumesNothing() {
        MutableClock clock = new MutableClock();
        TokenBucketRateLimiter limiter = limiter(1, 60, clock);

        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();
        // Still denied: the failed attempt must not have minted a token.
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();
    }

    @Test
    void tokensRefillAsClockAdvances() {
        MutableClock clock = new MutableClock();
        TokenBucketRateLimiter limiter = limiter(2, 60, clock);

        limiter.tryAcquire("client");
        limiter.tryAcquire("client");
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();

        // 60/min = 1/sec: advancing 61s restores the full bucket of 2.
        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();
    }

    @Test
    void refillNeverExceedsCapacity() {
        MutableClock clock = new MutableClock();
        TokenBucketRateLimiter limiter = limiter(2, 600, clock);

        clock.advance(Duration.ofHours(1));
        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client").allowed()).isFalse();
    }

    @Test
    void bucketsAreIsolatedPerClient() {
        MutableClock clock = new MutableClock();
        TokenBucketRateLimiter limiter = limiter(1, 60, clock);

        assertThat(limiter.tryAcquire("client-a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("client-a").allowed()).isFalse();
        // A different identity still has its own full bucket.
        assertThat(limiter.tryAcquire("client-b").allowed()).isTrue();
    }

    @Test
    void invalidConfigurationIsRejected() {
        MutableClock clock = new MutableClock();
        assertThatThrownBy(() -> limiter(0, 60, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(10, 0, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(-5, -5, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentAdmissionsNeverExceedCapacity() throws Exception {
        MutableClock clock = new MutableClock();
        TokenBucketRateLimiter limiter = limiter(5, 1, clock);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Workers did not start together");
                    }
                    return limiter.tryAcquire("shared-client").allowed();
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not become ready");
            }
            start.countDown();

            AtomicInteger admitted = new AtomicInteger();
            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    admitted.incrementAndGet();
                }
            }
            // Exactly capacity admitted regardless of thread scheduling: no
            // race can mint extra tokens, none can go negative.
            assertThat(admitted.get()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void trackedClientEntriesStayBounded() {
        MutableClock clock = new MutableClock();
        TokenBucketRateLimiter limiter = limiter(10, 60, clock);

        for (int i = 0; i < TokenBucketRateLimiter.MAX_TRACKED_CLIENTS + 50; i++) {
            limiter.tryAcquire("client-" + i);
        }
        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(TokenBucketRateLimiter.MAX_TRACKED_CLIENTS + 1);
    }
}

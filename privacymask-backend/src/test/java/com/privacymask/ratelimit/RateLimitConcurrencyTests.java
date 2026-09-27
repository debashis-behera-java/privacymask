package com.privacymask.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent authenticated burst against a capacity-5 bucket: exactly 5
 * requests are admitted and 5 receive 429, regardless of thread scheduling.
 * No race may mint extra tokens or corrupt state.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class RateLimitConcurrencyTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String VALID_KEY = "test-service-key";

    @DynamicPropertySource
    static void rateLimitOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.security.api-key", () -> VALID_KEY);
        registry.add("privacymask.rate-limit.enabled", () -> "true");
        registry.add("privacymask.rate-limit.capacity", () -> "5");
        registry.add("privacymask.rate-limit.refill-per-minute", () -> "1");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void concurrentBurstAdmitsExactlyCapacity() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Workers did not start together");
                    }
                    return webTestClient.post()
                            .uri(ANALYZE_URI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-API-Key", VALID_KEY)
                            .bodyValue(Map.of("text", "Concurrent request " + index + ".", "provider", "mock"))
                            .exchange()
                            .returnResult(String.class)
                            .getStatus()
                            .value();
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not become ready");
            }
            start.countDown();

            AtomicInteger admitted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            for (Future<Integer> future : futures) {
                int status = future.get(60, TimeUnit.SECONDS);
                if (status == 200) {
                    admitted.incrementAndGet();
                } else if (status == 429) {
                    rejected.incrementAndGet();
                } else {
                    throw new IllegalStateException("Unexpected status: " + status);
                }
            }
            assertThat(admitted.get()).isEqualTo(5);
            assertThat(rejected.get()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }
}

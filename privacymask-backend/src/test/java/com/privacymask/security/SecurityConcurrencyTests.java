package com.privacymask.security;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent authentication: correct and wrong keys interleaved across threads.
 * Each request authenticates independently; no cross-request state leakage.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class SecurityConcurrencyTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String VALID_KEY = "test-service-key";
    private static final String WRONG_KEY = "wrong-api-key";

    @DynamicPropertySource
    static void securityKey(DynamicPropertyRegistry registry) {
        registry.add("privacymask.security.api-key", () -> VALID_KEY);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void concurrentAuthRequestsStayIsolated() throws Exception {
        int count = 6;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Workers did not start together");
                    }
                    boolean valid = index % 2 == 0;
                    String key = valid ? VALID_KEY : WRONG_KEY;
                    String email = valid ? "valid" + index + "@example.com" : "invalid" + index + "@example.com";
                    return webTestClient.post()
                            .uri(ANALYZE_URI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-API-Key", key)
                            .bodyValue(Map.of("text", "Contact " + email + ".", "provider", "mock"))
                            .exchange()
                            .returnResult(Object.class)
                            .getStatus()
                            .value() == 200;
                }));
            }

            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not become ready");
            }
            start.countDown();

            for (int i = 0; i < count; i++) {
                boolean succeeded = futures.get(i).get(60, TimeUnit.SECONDS);
                if (i % 2 == 0) {
                    assertThat(succeeded).as("valid key request " + i + " should succeed").isTrue();
                } else {
                    assertThat(succeeded).as("wrong key request " + i + " should fail").isFalse();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void wrongKeyFollowedByCorrectKeyAuthenticatesNormally() {
        // Wrong key first.
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
                .bodyValue(Map.of("text", "Contact alice@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized();

        // Correct key should still work — no stuck auth state.
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Contact bob@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.processedText").isEqualTo("Contact {{EMAIL_001}}.");
    }

    @Test
    void correctKeyFollowedByWrongKeyDoesNotLeakAuth() {
        // Correct key first.
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Contact carol@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isOk();

        // Wrong key should still be rejected — no inherited auth.
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
                .bodyValue(Map.of("text", "Contact dave@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized();
    }
}

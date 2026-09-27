package com.privacymask.ratelimit;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * Token refill over HTTP: capacity 2 with a fast refill (120/min = 2/sec).
 * After exhaustion, a short bounded wait restores admission — no real-minute
 * sleeps (unit-level refill determinism lives in
 * {@link TokenBucketRateLimiterTests}).
 */
@SpringBootTest
@AutoConfigureWebTestClient
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitRefillApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String VALID_KEY = "test-service-key";

    @DynamicPropertySource
    static void rateLimitOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.security.api-key", () -> VALID_KEY);
        registry.add("privacymask.rate-limit.enabled", () -> "true");
        registry.add("privacymask.rate-limit.capacity", () -> "2");
        registry.add("privacymask.rate-limit.refill-per-minute", () -> "120");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @Order(1)
    void twoAllowedThirdDenied() {
        for (int i = 0; i < 2; i++) {
            webTestClient.post()
                    .uri(ANALYZE_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-API-Key", VALID_KEY)
                    .bodyValue(Map.of("text", "Hello " + i + ".", "provider", "mock"))
                    .exchange()
                    .expectStatus().isOk();
        }
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Hello denied.", "provider", "mock"))
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    @Order(2)
    void admissionRestoredAfterShortRefillWait() throws Exception {
        // 2 tokens/sec refill: 1.2s restores ~2.4 tokens, far above the
        // 1 token needed, so scheduling jitter cannot flake this.
        Thread.sleep(1200);
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Hello refilled.", "provider", "mock"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.response").isEqualTo("Hello refilled. Mock analysis completed.");
    }
}

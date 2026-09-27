package com.privacymask.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * {@code PRIVACYMASK_RATE_LIMIT_ENABLED=false} bypasses rate limiting only:
 * authenticated requests proceed without bound while authentication itself
 * (401s) and the status signal stay intact.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class RateLimitDisabledTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String STATUS_URI = "/api/v1/privacymask/status";
    private static final String VALID_KEY = "test-service-key";

    @DynamicPropertySource
    static void rateLimitOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.security.api-key", () -> VALID_KEY);
        registry.add("privacymask.rate-limit.enabled", () -> "false");
        registry.add("privacymask.rate-limit.capacity", () -> "1");
        registry.add("privacymask.rate-limit.refill-per-minute", () -> "1");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void authenticatedRequestsProceedUnlimitedWhenDisabled() {
        // Capacity is 1, yet every request succeeds: the limiter is off.
        for (int i = 0; i < 5; i++) {
            webTestClient.post()
                    .uri(ANALYZE_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-API-Key", VALID_KEY)
                    .bodyValue(Map.of("text", "Hello " + i + ".", "provider", "mock"))
                    .exchange()
                    .expectStatus().isOk();
        }
    }

    @Test
    void authenticationStillEnforcedWhenDisabled() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", "wrong-api-key")
                .bodyValue(Map.of("text", "Hello.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Hello.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void statusReportsRateLimitUnconfiguredWhenDisabled() {
        webTestClient.get()
                .uri(STATUS_URI)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.rateLimitConfigured").isEqualTo(false)
                .jsonPath("$.apiKey").doesNotExist();
    }
}

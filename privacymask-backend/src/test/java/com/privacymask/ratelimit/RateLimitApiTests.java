package com.privacymask.ratelimit;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Phase 12 proof: authentication first, rate limiting second,
 * PrivacyMask processing third.
 *
 * <p>Tiny deterministic capacity ({@code 3}, refill {@code 1/min} so no
 * accidental refill can occur mid-class). Methods are explicitly ordered
 * because they cumulatively consume the shared bucket.</p>
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String STATUS_URI = "/api/v1/privacymask/status";
    private static final String VALID_KEY = "test-service-key";
    private static final String WRONG_KEY = "wrong-api-key";

    @DynamicPropertySource
    static void rateLimitOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.security.api-key", () -> VALID_KEY);
        registry.add("privacymask.rate-limit.enabled", () -> "true");
        registry.add("privacymask.rate-limit.capacity", () -> "3");
        registry.add("privacymask.rate-limit.refill-per-minute", () -> "1");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @Order(1)
    void firstRequestAllowedWithNormalMasking() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.processedText").isEqualTo("Contact {{EMAIL_001}}.")
                .jsonPath("$.response").isEqualTo("Contact john@example.com. Mock analysis completed.");
    }

    @Test
    @Order(2)
    void burstUpToCapacityAllowed() {
        for (int i = 0; i < 2; i++) {
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
    @Order(3)
    void overCapacityReturns429JsonWithRetryAfter(CapturedOutput output) {
        String body = webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Hello over capacity.", "provider", "mock"))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().exists("Retry-After")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("\"status\":429", "Too many requests");
        assertThat(body).doesNotContain("stackTrace", "trace", VALID_KEY);
        // The credential and the PII-adjacent body never reach the logs.
        assertThat(output.getOut()).doesNotContain(VALID_KEY, "Hello over capacity");
        assertThat(output.getErr()).doesNotContain(VALID_KEY, "Hello over capacity");
    }

    @Test
    @Order(4)
    void unauthenticatedRequestsStillReturn401AfterExhaustion() {
        // Authentication remains the first boundary even when the bucket is
        // empty: wrong/missing keys get 401, never 429, and never consume quota.
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
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
    @Order(5)
    void rateLimitedPiiRequestProcessesNothing() {
        String piiText = "Customer john@example.com has SSN 123-45-6789 and card 4111 1111 1111 1111.";
        String body = webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", piiText, "provider", "mock"))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // No detection output, no masked tokens, no rehydrated response, no
        // original PII, no credential — the request stopped at the limiter.
        assertThat(body).doesNotContain(
                "john@example.com", "123-45-6789", "4111 1111 1111 1111",
                "{{EMAIL_001}}", "{{SSN_", "{{CREDIT_CARD_",
                "processedText", "response", VALID_KEY);
    }

    @Test
    @Order(6)
    void statusRemainsAvailableDuringRateLimitEvent() {
        webTestClient.get()
                .uri(STATUS_URI)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.rateLimitConfigured").isEqualTo(true)
                .jsonPath("$.apiKey").doesNotExist();
    }
}

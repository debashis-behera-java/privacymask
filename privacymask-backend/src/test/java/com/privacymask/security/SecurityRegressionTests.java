package com.privacymask.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authorized-flow regression: with a valid API key, the full PrivacyMask
 * pipeline (detection, masking, provider call, rehydration) works exactly as
 * before. Authentication is transparent to the core flow.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class SecurityRegressionTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String VALID_KEY = "test-service-key";

    @DynamicPropertySource
    static void securityKey(DynamicPropertyRegistry registry) {
        registry.add("privacymask.security.api-key", () -> VALID_KEY);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void authorizedMockProviderProcessesAndRehydrates() {
        String responseBody = webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of(
                        "text", "John's email is john@example.com and phone is +91 9876543210.",
                        "provider", "mock"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // processedText is masked (no raw PII in what the provider received).
        assertThat(responseBody).contains("\"processedText\":\"John's email is {{EMAIL_001}} and phone is {{PHONE_002}}.\"");
        // response is rehydrated (original values restored in the final reply).
        assertThat(responseBody).contains("john@example.com", "+91 9876543210");
        // But the raw PII must NOT appear in the processedText field.
        assertThat(responseBody).doesNotContain("\"processedText\":\"John's email is john@example.com");
    }

    @Test
    void authorizedRequestWithNoPiiWorks() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Please explain the refund policy.", "provider", "mock"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.processedText").isEqualTo("Please explain the refund policy.")
                .jsonPath("$.response").isEqualTo("Please explain the refund policy. Mock analysis completed.");
    }

    @Test
    void apiKeyNotSentToProvider() {
        // The API key must never appear in the provider request. With the mock
        // provider, the response echoes the sanitized input, so we can verify
        // the API key is not part of what the provider receives.
        String responseBody = webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Hello mock.", "provider", "mock"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(responseBody).doesNotContain(VALID_KEY);
    }

    @Test
    void duplicatePiiHandledCorrectlyWhenAuthorized() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of(
                        "text", "john@example.com contacted us. Please reply to john@example.com.",
                        "provider", "mock"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.processedText")
                .isEqualTo("{{EMAIL_001}} contacted us. Please reply to {{EMAIL_001}}.")
                .jsonPath("$.response")
                .isEqualTo("john@example.com contacted us. Please reply to john@example.com. Mock analysis completed.");
    }
}

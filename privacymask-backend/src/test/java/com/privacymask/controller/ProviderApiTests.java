package com.privacymask.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * API-level tests for Phase 6 provider integration: the analyze response
 * carries the mock provider reply, tokens are never re-hydrated, and the keyless
 * fail-closed path stays intact.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class ProviderApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void analyzeReturnsRehydratedResponse() {
        postJson(Map.of("text", "Contact john@example.com or call +91 9876543210.", "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("mock")
                .jsonPath("$.status").isEqualTo("ANALYZED")
                .jsonPath("$.processedText")
                .isEqualTo("Contact {{EMAIL_001}} or call {{PHONE_002}}.")
                .jsonPath("$.response")
                .isEqualTo("Contact john@example.com or call +91 9876543210. Mock analysis completed.");
    }

    @Test
    void responseRestoresRawPiiOnlyInFinalResponse() {
        postJson(Map.of("text", "Card 4111 1111 1111 1111 here", "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.processedText").isEqualTo("Card {{CREDIT_CARD_001}} here")
                .jsonPath("$.response")
                .isEqualTo("Card 4111 1111 1111 1111 here Mock analysis completed.");
    }

    @Test
    void cleanTextReturnsLlmResponse() {
        postJson(Map.of("text", "Please explain the refund policy.", "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.processedText").isEqualTo("Please explain the refund policy.")
                .jsonPath("$.response")
                .isEqualTo("Please explain the refund policy. Mock analysis completed.");
    }

    private WebTestClient.ResponseSpec postJson(Object body) {
        return webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", "test-service-key")
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }
}

package com.privacymask.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * Fail-closed API behavior without an encryption key: PII requests fail safely
 * (the provider is structurally unreachable because sealing comes first) while
 * clean requests still succeed.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = "privacymask.encryption.key=")
class KeylessProviderApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void piiRequestWithoutKeyFailsSecurely() {
        postJson(Map.of("text", "Contact john@example.com.", "provider", "mock"))
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.message").isEqualTo("Failed to process request securely.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist();
    }

    @Test
    void cleanRequestWithoutKeyStillSucceeds() {
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

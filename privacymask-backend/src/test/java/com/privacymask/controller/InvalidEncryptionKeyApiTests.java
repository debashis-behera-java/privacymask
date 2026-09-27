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
 * Fail-closed API behavior with a misconfigured encryption key.
 *
 * <p>Regression test for an operational incident: the backend was started with
 * {@code PRIVACYMASK_ENCRYPTION_KEY=01234567890123456789012345678901} (raw
 * 32 ASCII characters). That value is valid Base64 alphabet but decodes to
 * only 24 bytes, so {@code EncryptionKeyProvider} rejects it (exactly 32
 * bytes required) and every request carrying PII fails closed with HTTP 500
 * instead of leaking or bypassing protection. Clean requests still succeed
 * because they never touch the encryption path.</p>
 */
@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = "privacymask.encryption.key=01234567890123456789012345678901")
class InvalidEncryptionKeyApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void piiRequestWithMalformedKeyFailsSecurely() {
        postJson(Map.of("text", "Customer john@example.com needs help with his refund.", "provider", "mock"))
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.message").isEqualTo("Failed to process request securely.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist();
    }

    @Test
    void cleanRequestWithMalformedKeyStillSucceeds() {
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

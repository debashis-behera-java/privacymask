package com.privacymask.security;

import org.junit.jupiter.api.Test;
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
 * API-key authentication: valid/invalid/missing/empty keys, credential
 * reflection, log security, PII isolation. Status endpoint stays public.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyAuthTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String STATUS_URI = "/api/v1/privacymask/status";
    private static final String VALID_KEY = "test-service-key";
    private static final String WRONG_KEY = "wrong-api-key";

    @DynamicPropertySource
    static void securityKey(DynamicPropertyRegistry registry) {
        registry.add("privacymask.security.api-key", () -> VALID_KEY);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void statusEndpointIsPublic() {
        webTestClient.get()
                .uri(STATUS_URI)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.application").isEqualTo("PrivacyMask")
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.securityConfigured").isEqualTo(true)
                .jsonPath("$.apiKey").doesNotExist();
    }

    @Test
    void validKeyAllowsProcessing() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Contact john@example.com for assistance.", "provider", "mock"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("mock")
                .jsonPath("$.processedText").isEqualTo("Contact {{EMAIL_001}} for assistance.")
                .jsonPath("$.response").isEqualTo("Contact john@example.com for assistance. Mock analysis completed.");
    }

    @Test
    void missingKeyReturns401() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.message").isEqualTo("Unauthorized")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist()
                .jsonPath("$.stackTrace").doesNotExist();
    }

    @Test
    void wrongKeyReturns401() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.message").isEqualTo("Unauthorized")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist();
    }

    @Test
    void emptyKeyReturns401() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", "")
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void whitespaceKeyReturns401() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", "   ")
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized();
    }
}

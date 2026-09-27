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
 * Credential reflection, log security, PII isolation, and response format for
 * API-key authentication. Proves the credential never leaks and unauthorized
 * PII requests are fully blocked.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class ApiKeySecurityIsolationTests {

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
    void credentialIsNotReflected(CapturedOutput output) {
        String maliciousKey = "super-secret-value";
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", maliciousKey)
                .bodyValue(Map.of("text", "Hello.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(output.getOut()).doesNotContain(maliciousKey);
        assertThat(output.getErr()).doesNotContain(maliciousKey);
    }

    @Test
    void validKeyAndCredentialNotLogged(CapturedOutput output) {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isOk();

        assertThat(output.getOut()).doesNotContain(VALID_KEY);
        assertThat(output.getErr()).doesNotContain(VALID_KEY);
    }

    @Test
    void piiNotProcessedWhenUnauthorized() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
                .bodyValue(Map.of("text", "Customer john@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist();
    }

    @Test
    void piiNotExposedInUnauthorizedResponse(CapturedOutput output) {
        String piiText = "Customer john@example.com has card 4111 1111 1111 1111 and phone +91 9876543210.";
        String responseBody = webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
                .bodyValue(Map.of("text", piiText, "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(responseBody).doesNotContain("john@example.com", "4111 1111 1111 1111", "+91 9876543210");
        assertThat(output.getOut()).doesNotContain("john@example.com");
        assertThat(output.getErr()).doesNotContain("john@example.com");
    }

    @Test
    void responseIsJsonNotHtml() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Hello.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);
    }

    @Test
    void noSessionCreatedForUnauthorizedRequest() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Hello.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist("Set-Cookie");
    }
}

package com.privacymask.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security boundary proofs at the HTTP layer.
 *
 * <p>Authentication occurs before PII processing: unauthorized requests never
 * reach detection, masking, encryption, mapping, or any LLM provider.
 * The OpenAI/Anthropic zero-call proofs live beside the recording fake
 * servers (OpenAiAuthBoundaryTests / AnthropicAuthBoundaryTests), which can
 * access their package-private harnesses. Unknown routes keep a safe JSON
 * envelope, the alternate HTTP method stays protected, and the status
 * payload carries no credential material.</p>
 */
@SpringBootTest
@AutoConfigureWebTestClient
class SecurityBoundaryTests {

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
    void missingKeyYields401EnvelopeWithoutProcessing() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.error").isEqualTo("Unauthorized")
                .jsonPath("$.message").isEqualTo("Unauthorized")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist()
                .jsonPath("$.stackTrace").doesNotExist()
                .jsonPath("$.trace").doesNotExist();
    }

    @Test
    void invalidProviderWithWrongKeyReturns401Not400() {
        // Auth runs before provider validation: a bad key beats a bad provider.
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
                .bodyValue(Map.of("text", "Hello.", "provider", "no-such-provider"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.message").isEqualTo("Unauthorized");
    }

    @Test
    void oversizedUnauthorizedRequestIsRejectedWith401() {
        // Auth runs before the size guard: no key means 401 even when the
        // body also breaches the 10_000-char cap. Nothing is processed.
        String oversized = "a".repeat(12_000) + " john@example.com";
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
                .bodyValue(Map.of("text", oversized, "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class)
                .consumeWith(result -> assertThat(result.getResponseBody())
                        .doesNotContain("john@example.com"));
    }

    @Test
    void alternateMethodStaysProtected() {
        // GET on the analyze path without a key is rejected (401), never
        // routed into the pipeline.
        webTestClient.get()
                .uri(ANALYZE_URI)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void alternateMethodWithValidKeyDoesNotProcess() {
        // With a valid key, GET on the analyze path still cannot invoke the
        // POST-only pipeline: it fails with a 4xx, never a 200.
        webTestClient.get()
                .uri(ANALYZE_URI)
                .header("X-API-Key", VALID_KEY)
                .exchange()
                .expectStatus().isEqualTo(405);
    }

    @Test
    void unknownRouteKeepsSafeEnvelope() {
        String body = webTestClient.get()
                .uri("/api/v1/privacymask/does-not-exist")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        String safe = body == null ? "" : body;
        assertThat(safe).doesNotContain("stackTrace", "trace", VALID_KEY, WRONG_KEY);
    }

    @Test
    void statusBodyCarriesNoCredentialMaterial() {
        String body = webTestClient.get()
                .uri(STATUS_URI)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).doesNotContain(VALID_KEY, WRONG_KEY);
        assertThat(body).contains("\"securityConfigured\":true");
        assertThat(body).doesNotContain("apiKey", "api-key", "credential", "secret", "hash", "prefix");
    }

    @Test
    void piiHeavyUnauthorizedRequestLeaksNothing() {
        String piiText = "Customer john@example.com has card 4111 1111 1111 1111 "
                + "and phone +91 9876543210.";
        String body = webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
                .bodyValue(Map.of("text", piiText, "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).doesNotContain(
                "john@example.com", "4111 1111 1111 1111", "+91 9876543210",
                "{{EMAIL_001}}", "{{CREDIT_CARD_001}}", WRONG_KEY);
    }
}

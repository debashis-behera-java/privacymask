package com.privacymask.anthropic;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Proves the Phase 11 authentication boundary for the Anthropic path:
 * unauthorized requests produce zero outbound Anthropic calls
 * ("Unauthorized requests do not reach any LLM provider"), while an
 * authorized request produces exactly one — and the gateway API key never
 * travels to Anthropic.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class AnthropicAuthBoundaryTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String VALID_KEY = "test-service-key";
    private static final String WRONG_KEY = "wrong-api-key";

    private static final RecordingAnthropicServer SERVER = new RecordingAnthropicServer();

    @DynamicPropertySource
    static void anthropicOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.providers.anthropic.base-url", SERVER::baseUrl);
        registry.add("privacymask.providers.anthropic.api-key", () -> "test-anthropic-key");
        registry.add("privacymask.security.api-key", () -> VALID_KEY);
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void resetServer() {
        SERVER.reset();
    }

    @Test
    void wrongKeyMakesZeroAnthropicCalls() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "anthropic"))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(SERVER.requestCount()).isEqualTo(0);
    }

    @Test
    void missingKeyMakesZeroAnthropicCalls() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "anthropic"))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(SERVER.requestCount()).isEqualTo(0);
    }

    @Test
    void validKeyMakesExactlyOneAnthropicCallWithoutGatewayKey() {
        SERVER.setEchoInput(true);

        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "anthropic"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("anthropic");

        // No retries, no fallback: exactly one provider call.
        assertThat(SERVER.requestCount()).isEqualTo(1);
        RecordingAnthropicServer.Exchange exchange = SERVER.exchanges().get(0);
        // The gateway API key is configuration/secret material: it must never
        // be sent to Anthropic (neither in the body nor in headers).
        assertThat(exchange.body().toString()).doesNotContain(VALID_KEY);
        assertThat(exchange.apiKey() == null
                || !exchange.apiKey().contains(VALID_KEY)).isTrue();
    }
}

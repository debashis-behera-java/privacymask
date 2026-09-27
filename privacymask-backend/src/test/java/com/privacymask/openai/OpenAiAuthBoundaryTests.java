package com.privacymask.openai;

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
 * Proves the Phase 11 authentication boundary for the OpenAI path:
 * unauthorized requests produce zero outbound OpenAI calls
 * ("Unauthorized requests do not reach any LLM provider"), while an
 * authorized request produces exactly one — and the gateway API key never
 * travels to OpenAI.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class OpenAiAuthBoundaryTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String VALID_KEY = "test-service-key";
    private static final String WRONG_KEY = "wrong-api-key";

    private static final RecordingOpenAiServer SERVER = new RecordingOpenAiServer();

    @DynamicPropertySource
    static void openAiOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.providers.openai.base-url", SERVER::baseUrl);
        registry.add("privacymask.providers.openai.api-key", () -> "test-openai-key");
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
    void wrongKeyMakesZeroOpenAiCalls() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", WRONG_KEY)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "openai"))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(SERVER.requestCount()).isEqualTo(0);
    }

    @Test
    void missingKeyMakesZeroOpenAiCalls() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "openai"))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(SERVER.requestCount()).isEqualTo(0);
    }

    @Test
    void validKeyMakesExactlyOneOpenAiCallWithoutGatewayKey() {
        SERVER.setEchoInput(true);

        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "openai"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("openai");

        // No retries, no fallback: exactly one provider call.
        assertThat(SERVER.requestCount()).isEqualTo(1);
        RecordingOpenAiServer.Exchange exchange = SERVER.exchanges().get(0);
        // The gateway API key is configuration/secret material: it must never
        // be sent to OpenAI (neither in the body nor in headers).
        assertThat(exchange.body().toString()).doesNotContain(VALID_KEY);
        assertThat(exchange.authorization() == null
                || !exchange.authorization().contains(VALID_KEY)).isTrue();
    }
}

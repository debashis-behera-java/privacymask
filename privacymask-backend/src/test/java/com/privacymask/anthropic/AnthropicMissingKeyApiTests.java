package com.privacymask.anthropic;

import org.junit.jupiter.api.AfterAll;
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
 * Selecting {@code anthropic} with no API key configured fails safely at
 * request time: no outbound HTTP attempt, no fallback provider, no PII or key
 * material anywhere. The application starts keyless and keeps serving: status
 * stays functional and {@code mock} still works. (No key override here by
 * design.)
 */
@SpringBootTest
@AutoConfigureWebTestClient
class AnthropicMissingKeyApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final RecordingAnthropicServer SERVER = new RecordingAnthropicServer();

    @DynamicPropertySource
    static void anthropicBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("privacymask.providers.anthropic.base-url", SERVER::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void missingKeyMakesNoHttpAttempt() {
        postJson(Map.of("text", "Contact john@example.com now.", "provider", "anthropic"))
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.message").isEqualTo("LLM provider request failed.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist()
                .jsonPath("$.stackTrace").doesNotExist();

        assertThat(SERVER.requestCount()).isZero();
    }

    @Test
    void applicationStartsKeylessAndStatusAndMockStillWork() {
        webTestClient.get()
                .uri("/api/v1/privacymask/status")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.anthropicConfigured").isEqualTo(false)
                .jsonPath("$.anthropicApiKey").doesNotExist()
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.authorization").doesNotExist();

        postJson(Map.of("text", "Please explain the refund policy.", "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("mock")
                .jsonPath("$.response").value(response ->
                        assertThat((String) response).contains("Mock analysis"));
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
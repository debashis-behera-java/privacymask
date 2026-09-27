package com.privacymask.openai;

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
 * Selecting {@code openai} with no API key configured fails safely at request
 * time: no outbound HTTP attempt, no fallback provider, no PII or key material
 * anywhere. (No key override here by design.)
 */
@SpringBootTest
@AutoConfigureWebTestClient
class OpenAiMissingKeyApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final RecordingOpenAiServer SERVER = new RecordingOpenAiServer();

    @DynamicPropertySource
    static void openAiBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("privacymask.providers.openai.base-url", SERVER::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void missingKeyMakesNoHttpAttempt() {
        postJson(Map.of("text", "Contact john@example.com now.", "provider", "openai"))
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.message").isEqualTo("LLM provider request failed.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist()
                .jsonPath("$.stackTrace").doesNotExist();

        assertThat(SERVER.requestCount()).isZero();
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

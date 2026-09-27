package com.privacymask.openai;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Rate limiting on the OpenAI path: admitted requests each produce exactly
 * one outbound call; once exhausted, PII-bearing requests are rejected with
 * 429 and produce zero additional calls. No retries, no fallback.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenAiRateLimitBoundaryTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String VALID_KEY = "test-service-key";

    private static final RecordingOpenAiServer SERVER = new RecordingOpenAiServer();

    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.providers.openai.base-url", SERVER::baseUrl);
        registry.add("privacymask.providers.openai.api-key", () -> "test-openai-key");
        registry.add("privacymask.security.api-key", () -> VALID_KEY);
        registry.add("privacymask.rate-limit.enabled", () -> "true");
        registry.add("privacymask.rate-limit.capacity", () -> "2");
        registry.add("privacymask.rate-limit.refill-per-minute", () -> "1");
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @Order(1)
    void admittedRequestsEachProduceOneCall() {
        SERVER.setEchoInput(true);

        for (int i = 0; i < 2; i++) {
            webTestClient.post()
                    .uri(ANALYZE_URI)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-API-Key", VALID_KEY)
                    .bodyValue(Map.of("text", "Contact john" + i + "@example.com.", "provider", "openai"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.provider").isEqualTo("openai");
        }
        assertThat(SERVER.requestCount()).isEqualTo(2);
    }

    @Test
    @Order(2)
    void rateLimitedPiiRequestProducesZeroAdditionalCalls() {
        String body = webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of(
                        "text", "Customer john@example.com has SSN 123-45-6789.",
                        "provider", "openai"))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(SERVER.requestCount()).isEqualTo(2);
        assertThat(body).doesNotContain(
                "john@example.com", "123-45-6789", "{{EMAIL_001}}", VALID_KEY);
        for (RecordingOpenAiServer.Exchange exchange : SERVER.exchanges()) {
            assertThat(exchange.body().toString()).doesNotContain(VALID_KEY);
        }
    }
}

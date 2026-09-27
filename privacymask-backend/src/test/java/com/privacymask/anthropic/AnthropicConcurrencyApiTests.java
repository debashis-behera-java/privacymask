package com.privacymask.anthropic;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent Anthropic requests with echo replies: each request's masked text
 * travels alone, each token reply rehydrates to its own request's values, and
 * identical token numbers never cross-contaminate - including across requests
 * carrying different emails, phones, and cards. Threads are test-only.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class AnthropicConcurrencyApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String DUMMY_KEY = "test-anthropic-key";
    private static final RecordingAnthropicServer SERVER = new RecordingAnthropicServer();
    private static final int REQUESTS = 3;

    @DynamicPropertySource
    static void anthropicOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.providers.anthropic.base-url", SERVER::baseUrl);
        registry.add("privacymask.providers.anthropic.api-key", () -> DUMMY_KEY);
    }

    @BeforeAll
    static void echoMode() {
        SERVER.setEchoInput(true);
    }

    @BeforeEach
    void resetExchanges() {
        SERVER.reset();
        SERVER.setEchoInput(true);
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @SuppressWarnings("unchecked")
    void sameTokenRequestsStayIsolated() throws Exception {
        String[] users = {"john", "alice", "bob"};
        List<Map<String, Object>> bodies = runConcurrently(users.length,
                i -> "Help " + users[i] + "@example.com now.");

        Set<String> requestIds = new HashSet<>();
        for (int i = 0; i < users.length; i++) {
            requestIds.add((String) bodies.get(i).get("requestId"));
            assertThat((String) bodies.get(i).get("processedText"))
                    .isEqualTo("Help {{EMAIL_001}} now.");
            assertThat((String) bodies.get(i).get("response"))
                    .isEqualTo("Help " + users[i] + "@example.com now. done.");
            String other = users[(i + 1) % users.length] + "@example.com";
            assertThat((String) bodies.get(i).get("response")).doesNotContain(other);
        }
        assertThat(requestIds).hasSize(users.length);
        assertThat(SERVER.requestCount()).isEqualTo(users.length);
        for (RecordingAnthropicServer.Exchange exchange : SERVER.exchanges()) {
            assertThat(messagesContent(exchange.body())).isEqualTo("Help {{EMAIL_001}} now.");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentMultiPiiRequestsStayIsolated() throws Exception {
        String[] emails = {"john@example.com", "alice@example.com", "bob@example.com"};
        String[] phones = {"+91 9000000001", "+91 9000000002", "+91 9000000003"};
        String[] cards = {"4111 1111 1111 1111", "5555 5555 5555 4444", "4012 8888 8888 1881"};

        List<Map<String, Object>> bodies = runConcurrently(REQUESTS, i ->
                "User" + i + " contact " + emails[i] + " on " + phones[i] + " card " + cards[i] + ".");

        for (int i = 0; i < REQUESTS; i++) {
            Map<String, Object> body = bodies.get(i);
            assertThat((String) body.get("processedText")).isEqualTo(
                    "User" + i + " contact {{EMAIL_001}} on {{PHONE_002}} card {{CREDIT_CARD_003}}.");
            assertThat((String) body.get("response")).isEqualTo(
                    "User" + i + " contact " + emails[i] + " on " + phones[i]
                            + " card " + cards[i] + ". done.");

            for (int j = 0; j < REQUESTS; j++) {
                if (j == i) {
                    continue;
                }
                assertThat((String) body.get("response"))
                        .doesNotContain(emails[j], phones[j], cards[j]);
            }
        }
        assertThat(SERVER.requestCount()).isEqualTo(REQUESTS);
        for (RecordingAnthropicServer.Exchange exchange : SERVER.exchanges()) {
            String content = messagesContent(exchange.body());
            assertThat(content).contains("{{EMAIL_001}}", "{{PHONE_002}}", "{{CREDIT_CARD_003}}");
            assertThat(content).doesNotContain("john@example.com", "alice@example.com",
                    "+91 9000000001", "5555 5555 5555 4444");
        }
    }

    private static String messagesContent(com.fasterxml.jackson.databind.JsonNode body) {
        return body.path("messages").get(0).path("content").asText();
    }

    private List<Map<String, Object>> runConcurrently(int count, java.util.function.IntFunction<String> text)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Workers did not start together");
                    }
                    return webTestClient.post()
                            .uri(ANALYZE_URI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-API-Key", "test-service-key")
                            .accept(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("text", text.apply(index), "provider", "anthropic"))
                            .exchange()
                            .expectStatus().isOk()
                            .expectBody(Map.class)
                            .returnResult()
                            .getResponseBody();
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not become ready");
            }
            start.countDown();

            List<Map<String, Object>> bodies = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                bodies.add(future.get(60, TimeUnit.SECONDS));
            }
            return bodies;
        } finally {
            pool.shutdownNow();
        }
    }
}
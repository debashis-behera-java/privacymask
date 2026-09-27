package com.privacymask.openai;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * Concurrent OpenAI requests with echo replies: each request's masked text
 * travels alone, each token reply rehydrates to its own request's values, and
 * identical token numbers never cross-contaminate. Threads are test-only.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class OpenAiConcurrencyApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final RecordingOpenAiServer SERVER = new RecordingOpenAiServer();
    private static final int REQUESTS = 3;

    @DynamicPropertySource
    static void openAiOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.providers.openai.base-url", SERVER::baseUrl);
        registry.add("privacymask.providers.openai.api-key", () -> "test-openai-key");
    }

    @BeforeAll
    static void echoMode() {
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
    void concurrentOpenAiRequestsStayIsolated() throws Exception {
        String[] users = {"john", "alice", "bob"};
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        try {
            CountDownLatch ready = new CountDownLatch(REQUESTS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                final String user = users[i];
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
                            .bodyValue(Map.of(
                                    "text", "Help " + user + "@example.com now.",
                                    "provider", "openai"))
                            .exchange()
                            .expectStatus().isOk()
                            .expectBody(Map.class)
                            .returnResult()
                            .getResponseBody();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Map<String, Object>> bodies = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                bodies.add(future.get(60, TimeUnit.SECONDS));
            }

            Set<String> requestIds = new HashSet<>();
            for (int i = 0; i < REQUESTS; i++) {
                Map<String, Object> body = bodies.get(i);
                requestIds.add((String) body.get("requestId"));
                assertThat((String) body.get("processedText"))
                        .isEqualTo("Help {{EMAIL_001}} now.");
                assertThat((String) body.get("response"))
                        .isEqualTo("Help " + users[i] + "@example.com now. done.");
            }
            assertThat(requestIds).hasSize(REQUESTS);
            assertThat(SERVER.requestCount()).isEqualTo(REQUESTS);
            for (RecordingOpenAiServer.Exchange exchange : SERVER.exchanges()) {
                assertThat(exchange.body().path("input").asText())
                        .isEqualTo("Help {{EMAIL_001}} now.");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}

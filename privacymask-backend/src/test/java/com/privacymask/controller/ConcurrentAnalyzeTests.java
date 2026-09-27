package com.privacymask.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
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
 * Twelve simultaneous requests with distinct PII must stay fully isolated:
 * distinct request IDs, correct per-request masking/rehydration, same token
 * numbers resolving to each request's own values, and no cross-contamination.
 * Threads here are test-only; production code creates none.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class ConcurrentAnalyzeTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final int REQUESTS = 12;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @SuppressWarnings("unchecked")
    void concurrentRequestsRemainIsolated() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        try {
            CountDownLatch ready = new CountDownLatch(REQUESTS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Workers did not start together");
                    }
                    String email = "user" + index + "@example.com";
                    String phone = "+91 90000000" + String.format("%02d", index);
                    EntityExchangeResult<Map> result = webTestClient.post()
                            .uri(ANALYZE_URI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-API-Key", "test-service-key")
                            .accept(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of(
                                    "text", "User" + index + " contact " + email + " on " + phone + ".",
                                    "provider", "mock"))
                            .exchange()
                            .expectStatus().isOk()
                            .expectBody(Map.class)
                            .returnResult();
                    return result.getResponseBody();
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

            // Distinct request IDs.
            Set<String> requestIds = new HashSet<>();
            for (Map<String, Object> body : bodies) {
                requestIds.add((String) body.get("requestId"));
            }
            assertThat(requestIds).hasSize(REQUESTS);

            // Per-request correctness and isolation.
            for (int i = 0; i < REQUESTS; i++) {
                String email = "user" + i + "@example.com";
                String phone = "+91 90000000" + String.format("%02d", i);
                Map<String, Object> body = bodies.get(i);
                assertThat((String) body.get("processedText"))
                        .isEqualTo("User" + i + " contact {{EMAIL_001}} on {{PHONE_002}}.");
                assertThat((String) body.get("response"))
                        .isEqualTo("User" + i + " contact " + email + " on " + phone
                                + ". Mock analysis completed.");
                // No other request's PII leaked in.
                String otherEmail = "user" + ((i + 1) % REQUESTS) + "@example.com";
                assertThat((String) body.get("response")).doesNotContain(otherEmail);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}

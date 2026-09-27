package com.privacymask.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * Request-shape hardening: missing bodies, malformed JSON, wrong content
 * types, and wrong methods all fail with controlled client errors and never
 * reach the pipeline. No internals, bodies, or stack traces leak.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class RequestValidationTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void missingBodyIsRejected() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", "test-service-key")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.stackTrace").doesNotExist();
    }

    @Test
    void emptyObjectIsRejected() {
        postJson("{}")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void malformedJsonIsRejectedWithoutInternals() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", "test-service-key")
                .bodyValue("{invalid json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.stackTrace").doesNotExist()
                .jsonPath("$.trace").doesNotExist();
    }

    @Test
    void wrongContentTypeIsRejected() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-API-Key", "test-service-key")
                .bodyValue("{\"text\": \"hi\", \"provider\": \"mock\"}")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void getDoesNotInvokePipeline() {
        // Spring Security intercepts before the controller: unauthenticated
        // GET returns 401, not 405. Either way, the pipeline is never reached.
        webTestClient.get()
                .uri(ANALYZE_URI)
                .exchange()
                .expectStatus().isEqualTo(401);
    }

    @Test
    void nullTextIsRejected() {
        postJson("{\"text\": null, \"provider\": \"mock\"}")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
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

    @Test
    void validRequestStillSucceeds() {
        postJson(Map.of("text", "Hello", "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ANALYZED");
    }
}

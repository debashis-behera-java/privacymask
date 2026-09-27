package com.privacymask.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 CORS default: no origins configured, so real browser
 * cross-origin calls are rejected (403, no CORS headers) while non-browser
 * clients (no {@code Origin} header) are unaffected.
 *
 * <p>Uses a real (random-port) Netty server: CORS origin validation needs
 * absolute request URIs, which the mock test connector does not provide.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsDisabledTests {

    private static final String DEV_ORIGIN = "http://localhost:5173";

    @LocalServerPort
    private int port;

    @Autowired
    private CorsProperties corsProperties;

    private WebTestClient client;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void noOriginsConfiguredByDefault() {
        assertThat(corsProperties.origins()).isEmpty();
    }

    @Test
    void browserStatusCallRejectedByDefault() {
        client.get()
                .uri("/api/v1/privacymask/status")
                .accept(MediaType.APPLICATION_JSON)
                .header("Origin", DEV_ORIGIN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    void browserAnalyzeCallRejectedByDefault() {
        client.post()
                .uri("/api/v1/privacymask/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Origin", DEV_ORIGIN)
                .header("X-API-Key", "test-service-key")
                .bodyValue("{\"text\": \"Hello.\", \"provider\": \"mock\"}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    void nonBrowserCallsUnaffectedByDefault() {
        client.get()
                .uri("/api/v1/privacymask/status")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }
}

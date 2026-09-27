package com.privacymask.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * Phase 13 CORS development configuration: the exact Vite origin is
 * allowed and nothing else. Authentication and rate limiting apply
 * identically; {@code *} is never used and credentials are never allowed.
 *
 * <p>Uses a real (random-port) Netty server: CORS origin validation needs
 * absolute request URIs, which the mock test connector does not provide.
 * Distinct property values give this class its own Spring context.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsDevOriginTests {

    private static final String DEV_ORIGIN = "http://localhost:5173";
    private static final String EVIL_ORIGIN = "http://evil.example";
    private static final String VALID_KEY = "test-service-key";

    @DynamicPropertySource
    static void corsOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.security.api-key", () -> VALID_KEY);
        registry.add("privacymask.cors.allowed-origins", () -> DEV_ORIGIN);
        registry.add("privacymask.rate-limit.capacity", () -> "50");
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void preflightSucceedsForDevOrigin() {
        client.method(HttpMethod.OPTIONS)
                .uri("/api/v1/privacymask/analyze")
                .header("Origin", DEV_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-API-Key, Content-Type")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", DEV_ORIGIN);
    }

    @Test
    void authenticatedPostSucceedsWithCorsHeaders() {
        client.post()
                .uri("/api/v1/privacymask/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Origin", DEV_ORIGIN)
                .header("X-API-Key", VALID_KEY)
                .bodyValue(Map.of("text", "Hello.", "provider", "mock"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", DEV_ORIGIN);
    }

    @Test
    void unauthenticatedPostStill401WithCors() {
        client.post()
                .uri("/api/v1/privacymask/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Origin", DEV_ORIGIN)
                .bodyValue(Map.of("text", "Hello.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void unlistedOriginIsRejected() {
        client.get()
                .uri("/api/v1/privacymask/status")
                .accept(MediaType.APPLICATION_JSON)
                .header("Origin", EVIL_ORIGIN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }
}

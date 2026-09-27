package com.privacymask.security;

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
 * When no API key is configured, the application still starts but all
 * protected endpoints fail safely with HTTP 401. No default credential is
 * invented. Status remains public and reports securityConfigured: false.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class SecurityMissingKeyTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String STATUS_URI = "/api/v1/privacymask/status";

    @DynamicPropertySource
    static void clearApiKey(DynamicPropertyRegistry registry) {
        registry.add("privacymask.security.api-key", () -> "");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void applicationStartsWithoutApiKey() {
        // The mere fact that this test runs proves the application started
        // without a configured API key (no @DynamicPropertySource override).
        webTestClient.get()
                .uri(STATUS_URI)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void statusReportsSecurityUnconfigured() {
        webTestClient.get()
                .uri(STATUS_URI)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.securityConfigured").isEqualTo(false)
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.api-key").doesNotExist();
    }

    @Test
    void protectedEndpointFailsWithoutConfiguredKey() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", "any-key")
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.message").isEqualTo("Unauthorized")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist();
    }

    @Test
    void protectedEndpointFailsWithoutHeaderWhenKeyAbsent() {
        webTestClient.post()
                .uri(ANALYZE_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Contact john@example.com.", "provider", "mock"))
                .exchange()
                .expectStatus().isUnauthorized();
    }
}

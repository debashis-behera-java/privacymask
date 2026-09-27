package com.privacymask.anthropic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * With a (dummy) Anthropic key configured, the status endpoint reports
 * readiness without revealing anything about the key itself.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = "privacymask.providers.anthropic.api-key=dummy-test-key")
class StatusAnthropicConfiguredTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void reportsConfiguredWithoutRevealingKey() {
        webTestClient.get()
                .uri("/api/v1/privacymask/status")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.anthropicConfigured").isEqualTo(true)
                .jsonPath("$.anthropicApiKey").doesNotExist()
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.authorization").doesNotExist()
                .jsonPath("$.securityConfigured").isEqualTo(true);
    }
}
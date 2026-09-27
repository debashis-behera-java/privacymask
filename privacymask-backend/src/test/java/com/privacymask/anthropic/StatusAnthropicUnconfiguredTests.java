package com.privacymask.anthropic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The status endpoint reports Anthropic readiness as a boolean only - never
 * key material - and performs no external call (it answers with no key
 * configured and no server running). The configured-key case lives in
 * {@link StatusAnthropicConfiguredTests}.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class StatusAnthropicUnconfiguredTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void reportsUnconfiguredWithoutSecrets() {
        webTestClient.get()
                .uri("/api/v1/privacymask/status")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.application").isEqualTo("PrivacyMask")
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.anthropicConfigured").isEqualTo(false)
                .jsonPath("$.anthropicApiKey").doesNotExist()
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.authorization").doesNotExist()
                .jsonPath("$.securityConfigured").isEqualTo(true);
    }
}
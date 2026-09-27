package com.privacymask.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifies the Phase 1 status endpoint contract.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class StatusControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void statusReturnsOkWithExpectedPayload() {
        webTestClient.get()
                .uri("/api/v1/privacymask/status")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.application").isEqualTo("PrivacyMask")
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.version").isEqualTo("0.1.0");
    }
}

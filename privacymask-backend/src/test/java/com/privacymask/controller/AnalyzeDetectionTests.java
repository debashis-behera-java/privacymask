package com.privacymask.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * API-level tests for Phase 4 masking: masked {@code processedText}, token
 * numbering, duplicate reuse, clean text, invalid cards, and the guarantee that
 * token mappings never appear in the public response.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class AnalyzeDetectionTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void emailAndPhoneAreMaskedWithGlobalSequence() {
        String text = "Contact me at john@example.com or +91 9876543210.";

        postJson(Map.of("text", text, "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ANALYZED")
                .jsonPath("$.originalText").isEqualTo(text)
                .jsonPath("$.processedText")
                .isEqualTo("Contact me at {{EMAIL_001}} or {{PHONE_002}}.")
                .jsonPath("$.detections.length()").isEqualTo(2)
                .jsonPath("$.detections[0].type").isEqualTo("EMAIL")
                .jsonPath("$.detections[0].value").isEqualTo("john@example.com")
                .jsonPath("$.detections[0].start").isEqualTo(text.indexOf("john@example.com"))
                .jsonPath("$.detections[0].end")
                .isEqualTo(text.indexOf("john@example.com") + "john@example.com".length())
                .jsonPath("$.detections[1].type").isEqualTo("PHONE")
                .jsonPath("$.detections[1].value").isEqualTo("+91 9876543210")
                .jsonPath("$.detections[1].start").isEqualTo(text.indexOf("+91 9876543210"));
    }

    @Test
    void cardAndSsnAreMasked() {
        String text = "Card: 4111 1111 1111 1111 and SSN: 123-45-6789.";

        postJson(Map.of("text", text, "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ANALYZED")
                .jsonPath("$.processedText")
                .isEqualTo("Card: {{CREDIT_CARD_001}} and SSN: {{SSN_002}}.")
                .jsonPath("$.detections.length()").isEqualTo(2)
                .jsonPath("$.detections[0].type").isEqualTo("CREDIT_CARD")
                .jsonPath("$.detections[0].value").isEqualTo("4111 1111 1111 1111")
                .jsonPath("$.detections[1].type").isEqualTo("SSN")
                .jsonPath("$.detections[1].value").isEqualTo("123-45-6789");
    }

    @Test
    void duplicateValueReusesToken() {
        String text = "Email john@example.com. Confirm john@example.com.";

        postJson(Map.of("text", text, "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.processedText")
                .isEqualTo("Email {{EMAIL_001}}. Confirm {{EMAIL_001}}.")
                .jsonPath("$.detections.length()").isEqualTo(2);
    }

    @Test
    void cleanTextReturnsEmptyDetectionsUnchanged() {
        String text = "Hello, I need help with my order.";

        postJson(Map.of("text", text, "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ANALYZED")
                .jsonPath("$.processedText").isEqualTo(text)
                .jsonPath("$.detections.length()").isEqualTo(0);
    }

    @Test
    void invalidLuhnCardIsNotDetected() {
        postJson(Map.of("text", "Card 4111 1111 1111 1112 here", "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.detections.length()").isEqualTo(0);
    }

    @Test
    void publicResponseExposesNoTokenMappings() {
        postJson(Map.of("text", "Contact john@example.com", "provider", "mock"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.mappings").doesNotExist()
                .jsonPath("$.tokenMappings").doesNotExist()
                .jsonPath("$.originalValue").doesNotExist();
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
}

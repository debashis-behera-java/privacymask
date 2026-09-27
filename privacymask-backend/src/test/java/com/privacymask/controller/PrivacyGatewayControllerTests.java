package com.privacymask.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Gateway contract tests (Phase 2 contract as extended by Phase 3).
 *
 * <p>Phase 3 is detection-only: {@code processedText} must still equal
 * {@code originalText}, while {@code detections} reports what was found and
 * {@code status} is {@code ANALYZED}.</p>
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class PrivacyGatewayControllerTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";

    @Autowired
    private WebTestClient webTestClient;

    @Value("${privacymask.encryption.key}")
    private String configuredKey;

    @Test
    void validRequestReturnsAnalyzedWithEchoedText() {
        String text = "Customer John Doe contacted support regarding his order.";

        EntityExchangeResult<Map> result = postJson(Map.of("text", text, "provider", "mock"))
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody(Map.class)
                .value(body -> {
                    assertThat(body.get("provider")).isEqualTo("mock");
                    assertThat(body.get("status")).isEqualTo("ANALYZED");
                    assertThat(body.get("originalText")).isEqualTo(text);
                    assertThat(body.get("processedText")).isEqualTo(text);
                })
                .returnResult();

        String requestId = (String) result.getResponseBody().get("requestId");
        assertThat(requestId).isNotBlank();
        assertDoesNotThrow(() -> UUID.fromString(requestId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emailIsDetectedAndMasked() {
        String text = "Email me at john@example.com";

        postJson(Map.of("text", text, "provider", "mock"))
                .expectStatus().isOk()
                .expectBody(Map.class)
                .value(body -> {
                    assertThat(body.get("originalText")).isEqualTo(text);
                    assertThat(body.get("processedText")).isEqualTo("Email me at {{EMAIL_001}}");
                    assertThat((java.util.List<Map<String, Object>>) body.get("detections"))
                            .anySatisfy(detection -> {
                                assertThat(detection.get("type")).isEqualTo("EMAIL");
                                assertThat(detection.get("value")).isEqualTo("john@example.com");
                            });
                });
    }

    @Test
    void requestIdsAreUniqueAcrossRequests() {
        String first = extractRequestId(postJson(Map.of("text", "first", "provider", "mock")));
        String second = extractRequestId(postJson(Map.of("text", "second", "provider", "mock")));

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void missingTextReturnsBadRequest() {
        postJson("{\"provider\": \"mock\"}")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void blankTextReturnsBadRequest() {
        postJson(Map.of("text", "   ", "provider", "mock"))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void missingProviderReturnsBadRequest() {
        postJson("{\"text\": \"hello\"}")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void blankProviderReturnsBadRequest() {
        postJson(Map.of("text", "hello", "provider", "  "))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void unsupportedProviderReturnsBadRequestWithMessage() {
        postJson(Map.of("text", "hello", "provider", "xyz"))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.error").isEqualTo("Bad Request")
                .jsonPath("$.message").isEqualTo("Unsupported provider: xyz");
    }

    @Test
    void errorResponseNeverExposesStackTrace() {
        postJson(Map.of("text", "hello", "provider", "xyz"))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.stackTrace").doesNotExist()
                .jsonPath("$.trace").doesNotExist();
    }

    @Test
    void requestTextIsNeverLogged(CapturedOutput output) {
        String marker = "NOLOG-MARKER-" + UUID.randomUUID();

        postJson(Map.of("text", "secret payload " + marker, "provider", "mock"))
                .expectStatus().isOk();

        assertThat(output.getOut()).doesNotContain(marker);
        assertThat(output.getErr()).doesNotContain(marker);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detectedValuesAreNeverLogged(CapturedOutput output) {
        // Uses a unique local part so the value cannot appear in logs by coincidence.
        // 4111 1111 1111 1111 is the standard Luhn-valid TEST card number.
        String email = "nolog-" + UUID.randomUUID() + "@example.com";
        String card = "4111 1111 1111 1111";

        postJson(Map.of("text", "Contact " + email + " card " + card, "provider", "mock"))
                .expectStatus().isOk()
                .expectBody(Map.class)
                .value(body -> {
                    // Masked output carries tokens, never raw values.
                    assertThat((String) body.get("processedText"))
                            .doesNotContain(email, card)
                            .contains("{{EMAIL_001}}", "{{CREDIT_CARD_002}}");
                });

        assertThat(output.getOut()).doesNotContain(email).doesNotContain(card);
        assertThat(output.getErr()).doesNotContain(email).doesNotContain(card);
        // Only counts and type names may be logged.
        assertThat(output.getOut()).contains("tokenCount=2");
    }

    @Test
    void encryptionKeyIsNeverLogged(CapturedOutput output) {
        assertThat(configuredKey).isNotBlank();

        postJson(Map.of("text", "Contact john@example.com", "provider", "mock"))
                .expectStatus().isOk();

        assertThat(output.getOut()).doesNotContain(configuredKey);
        assertThat(output.getErr()).doesNotContain(configuredKey);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullSecurityScenarioKeepsSensitiveDataOutOfLogs(CapturedOutput output) {
        String text = "Customer john@example.com called +91 9876543210 regarding card 4111 1111 1111 1111.";

        postJson(Map.of("text", text, "provider", "mock"))
                .expectStatus().isOk()
                .expectBody(Map.class)
                .value(body -> {
                    // Re-hydration in the client response is intentional...
                    assertThat((String) body.get("response")).contains(
                            "john@example.com", "+91 9876543210", "4111 1111 1111 1111");
                    // ...but raw values must never reach logs, and mappings never the API.
                    assertThat(body).doesNotContainKey("mappings");
                });

        assertThat(output.getOut())
                .doesNotContain("john@example.com", "+91 9876543210", "4111 1111 1111 1111");
        assertThat(output.getErr())
                .doesNotContain("john@example.com", "+91 9876543210", "4111 1111 1111 1111");
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

    private String extractRequestId(WebTestClient.ResponseSpec spec) {
        EntityExchangeResult<Map> result = spec
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();
        return (String) result.getResponseBody().get("requestId");
    }
}

package com.privacymask.openai;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end OpenAI boundary proof through the real gateway: raw PII is
 * masked before the outbound HTTP call (captured byte-for-byte), scripted
 * token replies rehydrate, and nothing sensitive reaches logs.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class OpenAiBoundaryApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final RecordingOpenAiServer SERVER = new RecordingOpenAiServer();

    @DynamicPropertySource
    static void openAiOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.providers.openai.base-url", SERVER::baseUrl);
        // Dummy test key only (spec-sanctioned); unlocks the HTTP path under test.
        registry.add("privacymask.providers.openai.api-key", () -> "test-openai-key");
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void resetServer() {
        SERVER.reset();
    }

    @Test
    void outboundOpenAiRequestContainsTokensOnly() {
        String text = "Customer john@example.com called +91 9876543210 regarding card 4111 1111 1111 1111.";
        SERVER.setResponse(200, RecordingOpenAiServer.envelope(
                "Customer {{EMAIL_001}} called {{PHONE_002}} regarding card {{CREDIT_CARD_003}} ok."));

        postJson(Map.of("text", text, "provider", "openai"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("openai")
                .jsonPath("$.processedText").isEqualTo(
                        "Customer {{EMAIL_001}} called {{PHONE_002}} regarding card {{CREDIT_CARD_003}}.")
                .jsonPath("$.response").isEqualTo(
                        "Customer john@example.com called +91 9876543210 regarding card 4111 1111 1111 1111 ok.");

        assertThat(SERVER.requestCount()).isEqualTo(1);
        RecordingOpenAiServer.Exchange exchange = SERVER.exchanges().get(0);
        assertThat(exchange.method()).isEqualTo("POST");
        assertThat(exchange.path()).isEqualTo("/v1/responses");
        assertThat(exchange.authorization()).isNotBlank();
        String input = exchange.body().path("input").asText();
        assertThat(input).contains("{{EMAIL_001}}", "{{PHONE_002}}", "{{CREDIT_CARD_003}}");
        assertThat(input).doesNotContain("john@example.com", "9876543210", "4111 1111 1111 1111");
        // No mapping dictionary travels with the request.
        assertThat(exchange.body().toString()).doesNotContain("john@example.com");
    }

    @Test
    void multiPiiRehydratesFromOpenAiReply() {
        String text = "Email john@example.com, phone +91 9876543210, ssn 123-45-6789.";
        SERVER.setResponse(200, RecordingOpenAiServer.envelope(
                "Got {{EMAIL_001}} {{PHONE_002}} {{SSN_003}}"));

        postJson(Map.of("text", text, "provider", "openai"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.processedText").isEqualTo(
                        "Email {{EMAIL_001}}, phone {{PHONE_002}}, ssn {{SSN_003}}.")
                .jsonPath("$.response").isEqualTo(
                        "Got john@example.com +91 9876543210 123-45-6789");
    }

    @Test
    void duplicatePiiReusesTokenOverHttp() {
        SERVER.setResponse(200,
                RecordingOpenAiServer.envelope("{{EMAIL_001}} has been contacted."));

        postJson(Map.of(
                        "text", "Email john@example.com contacted us. Please reply to john@example.com.",
                        "provider", "openai"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.processedText").isEqualTo(
                        "Email {{EMAIL_001}} contacted us. Please reply to {{EMAIL_001}}.")
                .jsonPath("$.response").isEqualTo("john@example.com has been contacted.");

        assertThat(SERVER.exchanges().get(0).body().path("input").asText())
                .isEqualTo("Email {{EMAIL_001}} contacted us. Please reply to {{EMAIL_001}}.");
    }

    @Test
    void openAiPathKeepsSensitiveDataOutOfLogs(CapturedOutput output) {
        SERVER.setResponse(200, RecordingOpenAiServer.envelope("Noted {{EMAIL_001}}."));

        postJson(Map.of("text", "Contact john@example.com now.", "provider", "openai"))
                .expectStatus().isOk();

        assertThat(output.getOut()).doesNotContain("john@example.com");
        assertThat(output.getErr()).doesNotContain("john@example.com");
    }

    @Test
    void oversizedInputNeverReachesOpenAi() {
        String text = "john@example.com " + "x".repeat(10_000);

        postJson(Map.of("text", text, "provider", "openai"))
                .expectStatus().isEqualTo(413);

        assertThat(SERVER.requestCount()).isZero();
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

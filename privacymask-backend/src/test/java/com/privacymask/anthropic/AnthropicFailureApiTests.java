package com.privacymask.anthropic;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Upstream Anthropic failures become HTTP 502 with a fixed safe message: no
 * retry (exactly one outbound attempt), no PII, no key, no mapping, no
 * fallback. A dummy test key unlocks the HTTP path; the missing-key path is
 * covered by {@link AnthropicMissingKeyApiTests}.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class AnthropicFailureApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final String DUMMY_KEY = "test-anthropic-key";
    private static final RecordingAnthropicServer SERVER = new RecordingAnthropicServer();

    @DynamicPropertySource
    static void anthropicOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.providers.anthropic.base-url", SERVER::baseUrl);
        registry.add("privacymask.providers.anthropic.api-key", () -> DUMMY_KEY);
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    private WebTestClient webTestClient;

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500})
    void upstreamErrorMapsToSafe502(int status, CapturedOutput output) {
        SERVER.reset();
        SERVER.setResponse(status, "{\"error\":{\"type\":\"error\",\"message\":\"upstream says no\"}}");
        String email = "failure-probe@example.com";

        postJson(Map.of("text", "Contact " + email + " now.", "provider", "anthropic"))
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.message").isEqualTo("LLM provider request failed.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist()
                .jsonPath("$.stackTrace").doesNotExist();

        // Exactly one attempt - no automatic retry for 429/5xx, no fallback.
        assertThat(SERVER.requestCount()).isEqualTo(1);
        assertThat(output.getOut()).doesNotContain(email, "upstream says no", DUMMY_KEY);
        assertThat(output.getErr()).doesNotContain(email, "upstream says no", DUMMY_KEY);
    }

    @Test
    void authenticationFailureExposesNoCredential() {
        SERVER.reset();
        SERVER.setResponse(401, "{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");

        String responseBody = postJson(Map.of(
                        "text", "Probe the authentication path.",
                        "provider", "anthropic"))
                .expectStatus().isEqualTo(502)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(SERVER.requestCount()).isEqualTo(1);
        assertThat(responseBody).doesNotContain(DUMMY_KEY, "invalid x-api-key", "authentication_error");
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
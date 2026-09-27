package com.privacymask.openai;

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
 * Upstream OpenAI failures become HTTP 502 with a fixed safe message: no retry
 * (exactly one outbound attempt), no PII, no key, no mapping, no fallback.
 * A dummy test key unlocks the HTTP path; the missing-key path is covered by
 * {@link OpenAiMissingKeyApiTests}.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class OpenAiFailureApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";
    private static final RecordingOpenAiServer SERVER = new RecordingOpenAiServer();

    @DynamicPropertySource
    static void openAiOverrides(DynamicPropertyRegistry registry) {
        registry.add("privacymask.providers.openai.base-url", SERVER::baseUrl);
        registry.add("privacymask.providers.openai.api-key", () -> "test-openai-key");
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
        SERVER.setResponse(status, "{\"error\":{\"message\":\"upstream says no\"}}");
        String email = "failure-probe@example.com";

        postJson(Map.of("text", "Contact " + email + " now.", "provider", "openai"))
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.message").isEqualTo("LLM provider request failed.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist()
                .jsonPath("$.stackTrace").doesNotExist();

        assertThat(SERVER.requestCount()).isEqualTo(1);
        assertThat(output.getOut()).doesNotContain(email, "upstream says no");
        assertThat(output.getErr()).doesNotContain(email, "upstream says no");
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

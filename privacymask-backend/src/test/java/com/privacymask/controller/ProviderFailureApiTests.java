package com.privacymask.controller;

import com.privacymask.llm.LlmRequest;
import com.privacymask.llm.MockLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A failing provider yields HTTP 502 with a safe message: no raw PII, no
 * mappings, no original input as fallback, no retry, no alternate provider.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class ProviderFailureApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private MockLlmProvider mockProvider;

    @BeforeEach
    void stubFailingProvider() {
        when(mockProvider.name()).thenReturn("mock");
        when(mockProvider.complete(any(LlmRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("downstream exploded")));
    }

    @Test
    void providerFailureProducesControlled502(CapturedOutput output) {
        String email = "failure-probe@example.com";

        postJson(Map.of("text", "Contact " + email + " now.", "provider", "mock"))
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.error").isEqualTo("Bad Gateway")
                .jsonPath("$.message").isEqualTo("LLM provider request failed.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist()
                .jsonPath("$.stackTrace").doesNotExist();

        verify(mockProvider, times(1)).complete(any(LlmRequest.class));
        assertThat(output.getOut()).doesNotContain(email, "downstream exploded");
        assertThat(output.getErr()).doesNotContain(email, "downstream exploded");
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

package com.privacymask.controller;

import com.privacymask.llm.LlmRequest;
import com.privacymask.llm.LlmResponse;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hanging provider is bounded by the configured reactive timeout: the wait
 * is cancelled, the client gets HTTP 504 with no PII, and nothing falls back
 * to raw input. The delay itself is non-blocking ({@code Mono.delay}).
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "privacymask.provider.timeout=100ms")
class ProviderTimeoutApiTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private MockLlmProvider mockProvider;

    @BeforeEach
    void stubHangingProvider() {
        when(mockProvider.name()).thenReturn("mock");
        when(mockProvider.complete(any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            return Mono.delay(Duration.ofMillis(1500))
                    .thenReturn(new LlmResponse(request.requestId(), "mock", "too late"));
        });
    }

    @Test
    void timeoutProducesControlled504WithoutPii(CapturedOutput output) {
        String email = "timeout-probe@example.com";

        postJson(Map.of("text", "Contact " + email + " now.", "provider", "mock"))
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.status").isEqualTo(504)
                .jsonPath("$.error").isEqualTo("Gateway Timeout")
                .jsonPath("$.message").isEqualTo("LLM provider request timed out.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist()
                .jsonPath("$.stackTrace").doesNotExist();

        // Called exactly once (cancelled wait, never retried) and never again.
        verify(mockProvider, times(1)).complete(any(LlmRequest.class));
        assertThat(output.getOut()).doesNotContain(email);
        assertThat(output.getErr()).doesNotContain(email);
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

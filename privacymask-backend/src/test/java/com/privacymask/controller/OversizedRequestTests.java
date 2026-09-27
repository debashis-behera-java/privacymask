package com.privacymask.controller;

import com.privacymask.config.PrivacyMaskProperties;
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
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Oversized requests are rejected with HTTP 413 before detection, encryption,
 * mappings, or provider work - and the oversized body (PII included) is never
 * logged.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class OversizedRequestTests {

    private static final String ANALYZE_URI = "/api/v1/privacymask/analyze";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private MockLlmProvider mockProvider;

    // Read from the same bound properties the validator uses, so boundary
    // texts always track the limit actually enforced (test application.yml
    // shadows main config in tests, which is why @Value is avoided here).
    @Autowired
    private PrivacyMaskProperties properties;

    private int maxTextLength() {
        return properties.request().maxTextLength();
    }

    @BeforeEach
    void stubProvider() {
        when(mockProvider.name()).thenReturn("mock");
        when(mockProvider.complete(any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            return Mono.just(new LlmResponse(request.requestId(), "mock", "ok"));
        });
    }

    @Test
    void belowLimitSucceeds() {
        postJson(Map.of("text", "a".repeat(maxTextLength() - 10), "provider", "mock"))
                .expectStatus().isOk();
    }

    @Test
    void exactlyAtLimitSucceeds() {
        postJson(Map.of("text", "a".repeat(maxTextLength()), "provider", "mock"))
                .expectStatus().isOk();
    }

    @Test
    void aboveLimitIsRejectedWith413() {
        postJson(Map.of("text", "a".repeat(maxTextLength() + 1), "provider", "mock"))
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.status").isEqualTo(413)
                .jsonPath("$.error").isEqualTo("Payload Too Large")
                .jsonPath("$.message").value(message ->
                        assertThat((String) message).contains("maximum allowed length"))
                .jsonPath("$.stackTrace").doesNotExist();

        verify(mockProvider, never()).complete(any(LlmRequest.class));
    }

    @Test
    void oversizedPiiRequestNeverReachesPipelineOrLogs(CapturedOutput output) {
        int limit = maxTextLength();
        String email = "oversized-" + limit + "@example.com";
        String text = email + " " + "x".repeat(limit);

        postJson(Map.of("text", text, "provider", "mock"))
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.status").isEqualTo(413);

        verify(mockProvider, never()).complete(any(LlmRequest.class));
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

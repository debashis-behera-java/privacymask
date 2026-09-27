package com.privacymask.anthropic;

import com.privacymask.detection.DetectionEngine;
import com.privacymask.exception.MappingProtectionException;
import com.privacymask.mapping.ProtectedMappingService;
import com.privacymask.masking.MaskingService;
import com.privacymask.masking.TokenizationService;
import com.privacymask.processing.MaskingPrivacyProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Pipeline failures never reach Anthropic: masking, tokenization, and
 * encryption failures all stop the request with zero outbound provider HTTP
 * calls, no raw input sent anywhere, and no PII in logs or error bodies.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class AnthropicPipelineFailureTests {

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

    @MockBean
    private MaskingPrivacyProcessor maskingProcessor;

    @MockBean
    private TokenizationService tokenizationService;

    @MockBean
    private ProtectedMappingService mappingProtection;

    @Autowired
    private DetectionEngine detectionEngine;

    @Autowired
    private MaskingService maskingService;

    @Autowired
    private WebTestClient webTestClient;

    /**
     * A REAL processor over the REAL detection/masking stack (with the mocked
     * tokenization service injected), so tokenization/encryption failures are
     * exercised on the actual pipeline path rather than a stubbed shortcut.
     */
    private void delegateToRealPipeline() {
        MaskingPrivacyProcessor realProcessor =
                new MaskingPrivacyProcessor(detectionEngine, maskingService);
        when(maskingProcessor.process(any(String.class)))
                .thenAnswer(invocation -> realProcessor.process(invocation.getArgument(0)));
    }

    @Test
    void maskingFailurePreventsAnthropicCall(CapturedOutput output) {
        String email = "masking-probe@example.com";
        when(maskingProcessor.process(any(String.class)))
                .thenReturn(Mono.error(new IllegalStateException("masking exploded")));

        postJson(Map.of("text", "Contact " + email + " now.", "provider", "anthropic"))
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Unexpected error.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist();

        assertThat(SERVER.requestCount()).isZero();
        assertThat(output.getOut()).doesNotContain(email, "masking exploded");
        assertThat(output.getErr()).doesNotContain(email, "masking exploded");
    }

    @Test
    void tokenizationFailurePreventsAnthropicCall(CapturedOutput output) {
        String email = "tokenization-probe@example.com";
        delegateToRealPipeline();
        when(tokenizationService.tokenize(anyList()))
                .thenThrow(new IllegalStateException("tokenization exploded"));

        postJson(Map.of("text", "Contact " + email + " now.", "provider", "anthropic"))
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Unexpected error.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist();

        assertThat(SERVER.requestCount()).isZero();
        assertThat(output.getOut()).doesNotContain(email, "tokenization exploded");
        assertThat(output.getErr()).doesNotContain(email, "tokenization exploded");
    }

    @Test
    void encryptionFailurePreventsAnthropicCall(CapturedOutput output) {
        String email = "encryption-probe@example.com";
        delegateToRealPipeline();
        when(tokenizationService.tokenize(anyList())).thenReturn(List.of());
        when(mappingProtection.protect(any(UUID.class), anyList()))
                .thenReturn(Mono.error(new MappingProtectionException("seal failed")));

        postJson(Map.of("text", "Contact " + email + " now.", "provider", "anthropic"))
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Failed to process request securely.")
                .jsonPath("$.processedText").doesNotExist()
                .jsonPath("$.response").doesNotExist();

        assertThat(SERVER.requestCount()).isZero();
        assertThat(output.getOut()).doesNotContain(email, "seal failed", DUMMY_KEY);
        assertThat(output.getErr()).doesNotContain(email, "seal failed", DUMMY_KEY);
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
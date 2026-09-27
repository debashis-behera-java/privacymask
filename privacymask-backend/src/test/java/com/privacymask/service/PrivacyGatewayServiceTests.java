package com.privacymask.service;

import com.privacymask.config.PrivacyMaskProperties;
import com.privacymask.detection.PiiDetection;
import com.privacymask.detection.PiiType;
import com.privacymask.dto.AnalyzeRequest;
import com.privacymask.exception.DecryptionFailedException;
import com.privacymask.exception.MappingProtectionException;
import com.privacymask.exception.RequestTooLargeException;
import com.privacymask.exception.UnsupportedProviderException;
import com.privacymask.llm.LlmProvider;
import com.privacymask.llm.LlmProviderRegistry;
import com.privacymask.llm.LlmRequest;
import com.privacymask.llm.LlmResponse;
import com.privacymask.mapping.ProtectedMappingService;
import com.privacymask.masking.TokenMapping;
import com.privacymask.processing.PrivacyAnalysis;
import com.privacymask.processing.PrivacyProcessor;
import com.privacymask.rehydration.ResponseRehydrationService;
import com.privacymask.request.RequestSizeValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fail-closed orchestration tests: the provider is invoked if and only if the
 * secure pipeline produced sanitized text, and it always receives that
 * sanitized text - never raw input. No Spring context; collaborators are
 * mocked, the registry is real.
 */
class PrivacyGatewayServiceTests {

    private static final String RAW_TEXT = "Hi john@example.com";
    private static final String MASKED_TEXT = "Hi {{EMAIL_001}}";

    private final PrivacyProcessor processor = mock(PrivacyProcessor.class);
    private final ProtectedMappingService protection = mock(ProtectedMappingService.class);
    private final LlmProvider provider = mock(LlmProvider.class);
    private final ResponseRehydrationService rehydration = mock(ResponseRehydrationService.class);
    private PrivacyGatewayService service;

    @BeforeEach
    void setUp() {
        // Name must be stubbed before registry construction indexes it.
        when(provider.name()).thenReturn("mock");
        service = new PrivacyGatewayService(
                processor,
                protection,
                new LlmProviderRegistry(List.of(provider)),
                rehydration,
                new RequestSizeValidator(new PrivacyMaskProperties(null, null, null, null, null)),
                new PrivacyMaskProperties(null, null, null, null, null));
        // Default: pass provider replies through (individual tests override).
        when(rehydration.rehydrate(any(UUID.class), any(String.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(1)));
    }

    private PrivacyAnalysis analysis() {
        return new PrivacyAnalysis(
                MASKED_TEXT,
                List.of(new PiiDetection(PiiType.EMAIL, "john@example.com", 3, 19)),
                List.of(new TokenMapping("{{EMAIL_001}}", PiiType.EMAIL, "john@example.com", 3, 19)));
    }

    private void primeSuccess(String llmReply) {
        when(provider.name()).thenReturn("mock");
        when(processor.process(RAW_TEXT)).thenReturn(Mono.just(analysis()));
        when(protection.protect(any(UUID.class), anyList())).thenReturn(Mono.empty());
        when(provider.complete(any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest captured = invocation.getArgument(0);
            return Mono.just(new LlmResponse(captured.requestId(), "mock", llmReply));
        });
    }

    @Test
    void providerReceivesOnlySanitizedText() {
        primeSuccess("ok");
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);

        StepVerifier.create(service.analyze(new AnalyzeRequest(RAW_TEXT, "mock")))
                .assertNext(response -> {
                    assertThat(response.processedText()).isEqualTo(MASKED_TEXT);
                    assertThat(response.response()).isEqualTo("ok");
                })
                .verifyComplete();

        verify(provider).complete(captor.capture());
        assertThat(captor.getValue().sanitizedText().value()).isEqualTo(MASKED_TEXT);
        assertThat(captor.getValue().sanitizedText().value()).doesNotContain("john@example.com");
    }

    @Test
    void protectionFailurePreventsProviderCall() {
        when(provider.name()).thenReturn("mock");
        when(processor.process(RAW_TEXT)).thenReturn(Mono.just(analysis()));
        when(protection.protect(any(UUID.class), anyList()))
                .thenReturn(Mono.error(new MappingProtectionException("seal failed")));

        StepVerifier.create(service.analyze(new AnalyzeRequest(RAW_TEXT, "mock")))
                .verifyError(MappingProtectionException.class);

        verify(provider, never()).complete(any(LlmRequest.class));
    }

    @Test
    void maskingFailurePreventsProviderCall() {
        when(provider.name()).thenReturn("mock");
        when(processor.process(RAW_TEXT)).thenReturn(Mono.error(new RuntimeException("detector down")));

        StepVerifier.create(service.analyze(new AnalyzeRequest(RAW_TEXT, "mock")))
                .verifyErrorMessage("detector down");

        verify(protection, never()).protect(any(UUID.class), anyList());
        verify(provider, never()).complete(any(LlmRequest.class));
    }

    @Test
    void providerFailurePropagatesWithoutRawTextFallback() {
        when(provider.name()).thenReturn("mock");
        when(processor.process(RAW_TEXT)).thenReturn(Mono.just(analysis()));
        when(protection.protect(any(UUID.class), anyList())).thenReturn(Mono.empty());
        when(provider.complete(any(LlmRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("provider down")));

        // Provider errors are wrapped in a safe signal: no internals, no raw text.
        StepVerifier.create(service.analyze(new AnalyzeRequest(RAW_TEXT, "mock")))
                .expectErrorMatches(error -> error instanceof com.privacymask.exception.LlmProviderException
                        && "LLM provider request failed.".equals(error.getMessage()))
                .verify();
    }

    @Test
    void invalidProviderPreventsPipeline() {
        when(provider.name()).thenReturn("mock");

        StepVerifier.create(service.analyze(new AnalyzeRequest(RAW_TEXT, "xyz")))
                .verifyError(UnsupportedProviderException.class);

        verify(processor, never()).process(any(String.class));
        verify(provider, never()).complete(any(LlmRequest.class));
    }

    @Test
    void rehydrationFailureProducesNoPartialResponse() {
        primeSuccess("Hi {{EMAIL_001}}");
        when(rehydration.rehydrate(any(UUID.class), any(String.class)))
                .thenReturn(Mono.error(new DecryptionFailedException("tampered")));

        StepVerifier.create(service.analyze(new AnalyzeRequest(RAW_TEXT, "mock")))
                .verifyError(DecryptionFailedException.class);
    }

    @Test
    void oversizedTextStopsBeforePipeline() {
        String oversized = "x".repeat(10_001);

        StepVerifier.create(service.analyze(new AnalyzeRequest(oversized, "mock")))
                .verifyError(RequestTooLargeException.class);

        verify(processor, never()).process(any(String.class));
        verify(protection, never()).protect(any(UUID.class), anyList());
        verify(provider, never()).complete(any(LlmRequest.class));
    }

    @Test
    void clientCancellationCompletesSafely() {
        primeSuccess("ok");

        StepVerifier.create(service.analyze(new AnalyzeRequest(RAW_TEXT, "mock")))
                .thenCancel()
                .verify();
    }
}

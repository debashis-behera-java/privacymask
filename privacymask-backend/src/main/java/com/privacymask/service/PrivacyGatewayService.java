package com.privacymask.service;

import com.privacymask.dto.AnalyzeRequest;
import com.privacymask.dto.AnalyzeResponse;
import com.privacymask.config.PrivacyMaskProperties;
import com.privacymask.detection.PiiDetection;
import com.privacymask.exception.LlmProviderException;
import com.privacymask.exception.ProviderTimeoutException;
import com.privacymask.llm.LlmProvider;
import com.privacymask.llm.LlmProviderRegistry;
import com.privacymask.llm.LlmRequest;
import com.privacymask.llm.SanitizedText;
import com.privacymask.mapping.ProtectedMappingService;
import com.privacymask.model.ProcessingStatus;
import com.privacymask.processing.PrivacyAnalysis;
import com.privacymask.processing.PrivacyProcessor;
import com.privacymask.rehydration.ResponseRehydrationService;
import com.privacymask.request.RequestSizeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Orchestrates the gateway pipeline: request ID generation, provider
 * resolution, privacy processing (detection, tokenization, masking),
 * encryption of token mappings into the request-scoped store, sanitized LLM
 * invocation, secure re-hydration of the provider reply, and response assembly.
 *
 * <p>Boundary guarantee: the provider is invoked strictly after masking with a
 * {@link SanitizedText} built ONLY from masked pipeline output. There is no
 * raw-text fallback anywhere - if detection, protection, masking, provider, or
 * re-hydration fails, the error signal propagates and no partial output is
 * produced. {@code processedText} always stays masked; only the provider reply
 * is re-hydrated.</p>
 *
 * <p>Lifecycle order per request: request ID -&gt; size check -&gt; provider
 * validation -&gt; detection/tokenization/encryption/masking -&gt; bounded
 * provider call -&gt; re-hydration -&gt; response. Size and provider checks run
 * before any expensive or sensitive work, so rejected requests never create
 * mappings or reach the provider.</p>
 *
 * <p>Secure-logging rules: only {@code requestId}, {@code provider},
 * detection/token counts, detection types, and timing are logged. Request text,
 * detected values, token mappings, ciphertext, keys, and provider bodies are
 * never logged.</p>
 */
@Service
public class PrivacyGatewayService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyGatewayService.class);

    private final PrivacyProcessor privacyProcessor;
    private final ProtectedMappingService mappingProtection;
    private final LlmProviderRegistry providerRegistry;
    private final ResponseRehydrationService rehydrationService;
    private final RequestSizeValidator requestSizeValidator;
    private final java.time.Duration providerTimeout;

    public PrivacyGatewayService(PrivacyProcessor privacyProcessor,
                                 ProtectedMappingService mappingProtection,
                                 LlmProviderRegistry providerRegistry,
                                 ResponseRehydrationService rehydrationService,
                                 RequestSizeValidator requestSizeValidator,
                                 PrivacyMaskProperties properties) {
        this.privacyProcessor = privacyProcessor;
        this.mappingProtection = mappingProtection;
        this.providerRegistry = providerRegistry;
        this.rehydrationService = rehydrationService;
        this.requestSizeValidator = requestSizeValidator;
        this.providerTimeout = properties.provider().timeout();
    }

    /**
     * Processes an analyze request. Deferred so resolution failures also
     * surface as reactive error signals handled by the centralized handler.
     */
    public Mono<AnalyzeResponse> analyze(AnalyzeRequest request) {
        return Mono.defer(() -> {
            UUID requestId = UUID.randomUUID();
            // Cheap guards first: oversized or unknown-provider requests never
            // reach detection, encryption, mappings, or the provider.
            requestSizeValidator.validate(request.text());
            LlmProvider provider = providerRegistry.resolve(request.provider());
            long startNanos = System.nanoTime();
            return privacyProcessor.process(request.text())
                    .flatMap(analysis -> mappingProtection.protect(requestId, analysis.mappings())
                            .then(invokeProvider(provider, requestId, request, analysis, startNanos)))
                    .doOnError(error -> log.warn(
                            "PrivacyMask request failed requestId={} provider={}",
                            requestId,
                            provider.name()));
        });
    }

    private Mono<AnalyzeResponse> invokeProvider(LlmProvider provider, UUID requestId,
                                                 AnalyzeRequest request, PrivacyAnalysis analysis,
                                                 long startNanos) {
        return Mono.defer(() -> {
            // The ONLY provider input: masked pipeline output. No raw text exists
            // on this path by construction (LlmRequest accepts SanitizedText only).
            LlmRequest llmRequest = new LlmRequest(
                    requestId, provider.name(), SanitizedText.masked(analysis.processedText(), requestId));
            return provider.complete(llmRequest)
                    // Reactive bound only: no threads, no blocking. A breach
                    // cancels the wait and fails closed - never retried, never
                    // replaced with raw input.
                    .timeout(providerTimeout)
                    .onErrorMap(TimeoutException.class,
                            e -> new ProviderTimeoutException("LLM provider request timed out.", e))
                    .onErrorMap(e -> !(e instanceof ProviderTimeoutException),
                            e -> new LlmProviderException("LLM provider request failed.", e))
                    .flatMap(llmResponse -> rehydrationService.rehydrate(requestId, llmResponse.responseText()))
                    .doOnSuccess(finalResponse -> log.info(
                            "PrivacyMask analysis completed requestId={} provider={} status={} detectionCount={} tokenCount={} detectionTypes={} durationMs={}",
                            requestId,
                            provider.name(),
                            ProcessingStatus.ANALYZED,
                            analysis.detections().size(),
                            analysis.mappings().size(),
                            detectionTypeNames(analysis.detections()),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)))
                    .map(finalResponse -> new AnalyzeResponse(
                            requestId,
                            request.text(),
                            analysis.processedText(),
                            provider.name(),
                            ProcessingStatus.ANALYZED,
                            analysis.detections(),
                            finalResponse));
        });
    }

    /**
     * Distinct detection type names for logs. Types (e.g. EMAIL) are safe to log;
     * detected values never are.
     */
    private String detectionTypeNames(List<PiiDetection> detections) {
        return detections.stream()
                .map(detection -> detection.type().name())
                .distinct()
                .sorted()
                .collect(Collectors.joining(",", "[", "]"));
    }
}

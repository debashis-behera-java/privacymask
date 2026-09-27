package com.privacymask.llm;

import reactor.core.publisher.Mono;

/**
 * Application-facing LLM capability, behind the privacy boundary.
 *
 * <p>Implementations know NOTHING about PII detection, encryption keys, or
 * token mappings - they receive {@link SanitizedText} and return an
 * {@link LlmResponse}. Phase 6 binds {@link MockLlmProvider} only; vendor
 * providers (OpenAI, Anthropic) implement this same interface later.</p>
 *
 * <p>Implementations must be non-blocking and must never fall back to raw
 * text: the only valid input is the sanitized text handed to them.</p>
 */
public interface LlmProvider {

    /**
     * Provider name as used in API requests (e.g. {@code "mock"}).
     */
    String name();

    /**
     * Completes the sanitized request without blocking.
     */
    Mono<LlmResponse> complete(LlmRequest request);
}

package com.privacymask.llm;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local deterministic stand-in for an external LLM. Makes no network calls and
 * needs no credentials.
 *
 * <p>To exercise re-hydration, the mock echoes the sanitized input verbatim
 * plus a fixed suffix - so token-bearing responses flow back without the mock
 * ever seeing raw PII (its input is sanitized by construction). Deterministic
 * per input.</p>
 *
 * <p>Test support: the last received request and the call count are retained in
 * instance state for boundary verification (was sanitized text delivered?
 * exactly once? never after pipeline failure?). This capture is test-only -
 * production code never reads it, it is never logged, and it is reset between
 * tests. No static global state.</p>
 */
@Component
public class MockLlmProvider implements LlmProvider {

    static final String SUFFIX = " Mock analysis completed.";

    private final AtomicReference<LlmRequest> lastReceived = new AtomicReference<>();
    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public Mono<LlmResponse> complete(LlmRequest request) {
        if (request == null) {
            return Mono.error(new IllegalArgumentException("LLM request must not be null."));
        }
        // In-memory capture only (see class javadoc); never logged.
        lastReceived.set(request);
        callCount.incrementAndGet();
        return Mono.just(new LlmResponse(
                request.requestId(), name(), request.sanitizedText().value() + SUFFIX));
    }

    /** Test-only: the last request that crossed the boundary, if any. */
    LlmRequest lastReceived() {
        return lastReceived.get();
    }

    /** Test-only: how many times the provider was invoked. */
    int callCount() {
        return callCount.get();
    }

    /** Test-only: clears captured boundary state. */
    void reset() {
        lastReceived.set(null);
        callCount.set(0);
    }
}

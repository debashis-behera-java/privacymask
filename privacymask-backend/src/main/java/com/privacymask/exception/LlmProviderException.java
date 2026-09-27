package com.privacymask.exception;

/**
 * Raised when an LLM provider call fails (transport, model, or downstream
 * error surfaced reactively).
 *
 * <p>Carries no prompt, response, PII, or provider internals - only the fact
 * of failure. Handled as HTTP 502. Never triggers a raw-text fallback.</p>
 */
public class LlmProviderException extends RuntimeException {

    public LlmProviderException(String message) {
        super(message);
    }

    public LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}

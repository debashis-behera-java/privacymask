package com.privacymask.exception;

/**
 * Raised when an LLM provider call exceeds the configured reactive timeout.
 *
 * <p>Handled as HTTP 504 with no prompt, response, PII, or timeout internals.
 * The timed-out call is cancelled; it is never retried automatically and never
 * replaced with raw input.</p>
 */
public class ProviderTimeoutException extends RuntimeException {

    public ProviderTimeoutException(String message) {
        super(message);
    }

    public ProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

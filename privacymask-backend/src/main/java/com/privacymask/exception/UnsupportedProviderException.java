package com.privacymask.exception;

/**
 * Raised when a client requests an LLM provider the gateway does not support.
 *
 * <p>Handled centrally by {@link GlobalExceptionHandler} as HTTP 400 without
 * exposing stack traces or internals. The offending provider value is a short
 * client-supplied identifier (never request text or PII) and is safe to echo.</p>
 */
public class UnsupportedProviderException extends RuntimeException {

    private final String provider;

    public UnsupportedProviderException(String provider) {
        super("Unsupported provider: " + provider);
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }
}

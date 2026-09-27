package com.privacymask.exception;

/**
 * Raised when token mappings cannot be protected (encrypted and stored).
 *
 * <p>The pipeline must fail closed on this exception: no masked response is
 * returned and no plaintext mapping is retained. Carries no PII, ciphertext, or
 * key material.</p>
 */
public class MappingProtectionException extends RuntimeException {

    public MappingProtectionException(String message) {
        super(message);
    }

    public MappingProtectionException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.privacymask.exception;

/**
 * Raised when an {@code encrypt} operation cannot be completed.
 *
 * <p>Carries no plaintext, key material, or cryptographic internals. Callers
 * must fail closed on this exception - never fall back to storing plaintext.</p>
 */
public class EncryptionFailedException extends RuntimeException {

    public EncryptionFailedException(String message) {
        super(message);
    }

    public EncryptionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.privacymask.exception;

/**
 * Raised when decryption fails: tampered ciphertext/IV, wrong key, malformed
 * input, or authentication-tag mismatch.
 *
 * <p>Carries no plaintext (not even partial) and no key material. Handled as a
 * controlled internal error.</p>
 */
public class DecryptionFailedException extends RuntimeException {

    public DecryptionFailedException(String message) {
        super(message);
    }

    public DecryptionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}

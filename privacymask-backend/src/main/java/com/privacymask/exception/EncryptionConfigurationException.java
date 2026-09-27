package com.privacymask.exception;

/**
 * Raised when the AES key configuration is missing or invalid (absent value,
 * malformed Base64, or wrong decoded length).
 *
 * <p>Carries no key material in the message. Handled centrally as HTTP 500 with
 * a generic message - a broken key configuration is a server-side problem.</p>
 */
public class EncryptionConfigurationException extends RuntimeException {

    public EncryptionConfigurationException(String message) {
        super(message);
    }

    public EncryptionConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

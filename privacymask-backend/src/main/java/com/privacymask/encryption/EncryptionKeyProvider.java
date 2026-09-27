package com.privacymask.encryption;

import com.privacymask.config.PrivacyMaskProperties;
import com.privacymask.exception.EncryptionConfigurationException;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.util.Base64;

/**
 * Resolves and validates the configured AES-256 key.
 *
 * <p>Validation is lazy but strict: the key is decoded from Base64 and checked
 * for exactly 32 bytes on every resolution. A missing, malformed, or short/long
 * key fails clearly via {@link EncryptionConfigurationException} when
 * encryption is invoked - never silently replaced with a weak, truncated, or
 * per-request random key. Key material never appears in messages or logs.</p>
 */
@Component
public class EncryptionKeyProvider {

    private static final int AES_256_KEY_BYTES = 32;

    private final PrivacyMaskProperties properties;

    public EncryptionKeyProvider(PrivacyMaskProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the configured AES key, validating it first.
     *
     * @throws EncryptionConfigurationException if no valid key is configured
     */
    public Key getKey() {
        String configured = properties.encryption() == null ? null : properties.encryption().key();
        if (configured == null || configured.isBlank()) {
            throw new EncryptionConfigurationException(
                    "Encryption key is not configured. Set the PRIVACYMASK_ENCRYPTION_KEY "
                            + "environment variable to a Base64-encoded 32-byte key.");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new EncryptionConfigurationException(
                    "Encryption key is not valid Base64.", e);
        }
        if (decoded.length != AES_256_KEY_BYTES) {
            throw new EncryptionConfigurationException(
                    "Encryption key must decode to exactly 32 bytes (256 bits).");
        }
        return new SecretKeySpec(decoded, "AES");
    }
}

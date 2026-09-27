package com.privacymask.mapping;

import com.privacymask.detection.PiiType;
import com.privacymask.encryption.EncryptedValue;

/**
 * Protected token assignment: the token plus the AES-256-GCM sealed original
 * value and its first-occurrence offsets.
 *
 * <p>Strictly internal - never serialized to API responses, logs, or storage.
 * Holds no plaintext and no key material. The layout mirrors
 * {@code TokenMapping} so Phase 5 protection is a field-for-field replacement
 * of {@code originalValue} with {@code encryptedValue}.</p>
 */
public record EncryptedTokenMapping(
        String token,
        PiiType type,
        EncryptedValue encryptedValue,
        int start,
        int end) {
}

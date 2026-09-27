package com.privacymask.masking;

import com.privacymask.detection.PiiDetection;
import com.privacymask.detection.PiiType;

import java.util.Locale;

/**
 * Duplicate-value policy for token assignment, centralized so tokenization and
 * masking resolve spans identically.
 *
 * <p>Policy: two occurrences share a token when they have the same type and the
 * same value, where EMAIL values compare case-insensitively ( mailbox names are
 * practically case-insensitive while differing capitalizations still denote the
 * same address). All other types compare exactly - e.g. URL paths are
 * case-sensitive resources and must not be conflated. The stored
 * {@code originalValue} is always the exact first-seen substring.</p>
 */
final class TokenKeys {

    private TokenKeys() {
    }

    static String keyOf(PiiDetection detection) {
        return keyOf(detection.type(), detection.value());
    }

    static String keyOf(PiiType type, String value) {
        String normalized = type == PiiType.EMAIL ? value.toLowerCase(Locale.ROOT) : value;
        return type.name() + "\0" + normalized;
    }
}

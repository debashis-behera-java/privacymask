package com.privacymask.masking;

import com.privacymask.detection.PiiType;

/**
 * In-memory token assignment for one detected PII value.
 *
 * <p>{@code start}/{@code end} locate the FIRST occurrence in the original text;
 * repeated occurrences reuse the same token. {@code originalValue} is the exact
 * first-seen substring (never normalized).</p>
 *
 * <p>Phase 5 compatibility: the layout is kept stable so {@code originalValue}
 * can be replaced by an encrypted/protected reference without changing callers.
 * Mappings are never persisted, never logged, and never exposed via the public
 * API in Phase 4.</p>
 */
public record TokenMapping(
        String token,
        PiiType type,
        String originalValue,
        int start,
        int end) {
}

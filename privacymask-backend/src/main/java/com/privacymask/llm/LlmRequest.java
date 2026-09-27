package com.privacymask.llm;

import java.util.UUID;

/**
 * Internal provider invocation, constructed by the trusted pipeline AFTER
 * masking - never by the controller from raw client text.
 *
 * <p>Carries {@link SanitizedText} (not a {@code String}) so the boundary is
 * structural: there is no overload, fallback, or code path that accepts raw
 * input here.</p>
 */
public record LlmRequest(UUID requestId, String provider, SanitizedText sanitizedText) {
}

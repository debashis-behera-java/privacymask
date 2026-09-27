package com.privacymask.masking;

import java.util.List;

/**
 * Internal result of masking one request text.
 *
 * <p>Strictly pipeline-internal: only {@code maskedText} (via the gateway
 * response's {@code processedText}) leaves the trust boundary. {@code mappings}
 * stay in memory for the request and are never serialized to clients, logs, or
 * storage in Phase 4.</p>
 */
public record MaskingResult(
        String maskedText,
        List<TokenMapping> mappings) {

    public MaskingResult {
        maskedText = maskedText == null ? "" : maskedText;
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
    }
}

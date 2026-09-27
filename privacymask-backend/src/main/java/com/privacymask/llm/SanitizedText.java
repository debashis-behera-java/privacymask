package com.privacymask.llm;

import java.util.UUID;

/**
 * Text that has passed the full secure pipeline (detection, tokenization,
 * encryption of mappings, masking) and is therefore the ONLY text allowed to
 * cross the LLM provider boundary.
 *
 * <p>Type safety over convention: provider operations accept this wrapper, not
 * a raw {@code String}, so raw client text cannot be passed accidentally. The
 * sole sanctioned origin is masked pipeline output - see the {@link #masked}
 * factory. The {@code requestId} binds the text to its request for correlation
 * (never as key material).</p>
 */
public record SanitizedText(String value, UUID requestId) {

    /**
     * Wraps pipeline-masked output. Call only with text produced by masking;
     * never with raw client input.
     */
    public static SanitizedText masked(String maskedValue, UUID requestId) {
        return new SanitizedText(maskedValue, requestId);
    }
}

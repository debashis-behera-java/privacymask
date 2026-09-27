package com.privacymask.processing;

import reactor.core.publisher.Mono;

/**
 * Extension point for the privacy pipeline.
 *
 * <p>Phase 4 binds {@link MaskingPrivacyProcessor} (detection, tokenization,
 * masking) as the active pipeline; {@link NoOpPrivacyProcessor} remains as the
 * tested no-op baseline. Later phases extend the active processor (encryption,
 * LLM dispatch) without touching the controller or gateway service.</p>
 */
public interface PrivacyProcessor {

    /**
     * Runs the privacy pipeline over the given text.
     *
     * @param text the original request text, never {@code null}
     * @return the analysis (processed text, detections, and token mappings)
     */
    Mono<PrivacyAnalysis> process(String text);
}

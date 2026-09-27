package com.privacymask.processing;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Baseline pass-through processor: returns the input text unchanged with no
 * detections and no mappings.
 *
 * <p>Not the active pipeline (see {@link MaskingPrivacyProcessor}), but retained
 * as the tested no-op baseline and as proof of the {@link PrivacyProcessor}
 * seam.</p>
 */
@Component
public class NoOpPrivacyProcessor implements PrivacyProcessor {

    @Override
    public Mono<PrivacyAnalysis> process(String text) {
        return Mono.just(new PrivacyAnalysis(text, List.of(), List.of()));
    }
}

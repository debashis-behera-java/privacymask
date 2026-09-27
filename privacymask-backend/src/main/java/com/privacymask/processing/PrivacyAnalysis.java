package com.privacymask.processing;

import com.privacymask.detection.PiiDetection;
import com.privacymask.masking.TokenMapping;

import java.util.List;

/**
 * Result of one privacy-pipeline pass over the request text.
 *
 * <p>Phase 5: {@code processedText} is the masked text; {@code mappings} are
 * transient plaintext assignments that the gateway seals into the encrypted,
 * request-scoped store immediately after this result is produced. They are
 * never logged, persisted, or exposed publicly - their in-memory lifetime is
 * limited to this single handoff.</p>
 */
public record PrivacyAnalysis(
        String processedText,
        List<PiiDetection> detections,
        List<TokenMapping> mappings) {

    public PrivacyAnalysis {
        processedText = processedText == null ? "" : processedText;
        detections = detections == null ? List.of() : List.copyOf(detections);
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
    }
}

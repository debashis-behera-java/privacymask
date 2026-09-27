package com.privacymask.detection;

import java.util.List;

/**
 * Extension point for PII detection.
 *
 * <p>Phase 3 binds {@link RegexPiiDetector} only. A future NLP detector implements
 * this same interface and is picked up automatically by {@link DetectionEngine},
 * which merges and de-duplicates candidates from all detectors.</p>
 *
 * <p>Implementations must be null-safe (return an empty list for {@code null} or
 * empty input, never throw {@link NullPointerException}) and must report offsets
 * against the original, unmodified input.</p>
 */
public interface PiiDetector {

    /**
     * Finds PII candidates in the given text.
     *
     * @param text the text to scan, may be {@code null}
     * @return detections in any order (the engine sorts deterministically),
     *         never {@code null}
     */
    List<PiiDetection> detect(String text);
}

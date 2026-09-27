package com.privacymask.detection;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Merges candidates from all registered {@link PiiDetector} beans into one
 * deterministic result.
 *
 * <p>Overlap strategy (documented contract):</p>
 * <ol>
 *   <li>Candidates are considered in order of start offset, then
 *       {@link PiiType#priority()} (more specific shapes first), then longer
 *       span first.</li>
 *   <li>A candidate is kept only if it does not overlap an already-kept span,
 *       so a card number is never also reported as a phone and the same span is
 *       never returned twice.</li>
 *   <li>The final list is sorted by start offset with deterministic
 *       tie-breakers (end offset, then type).</li>
 * </ol>
 *
 * <p>Overlap checks are a linear scan because per-request candidate counts are
 * tiny - correctness first, no premature optimization. Null-safe throughout.</p>
 */
@Component
public class DetectionEngine {

    private final List<PiiDetector> detectors;

    public DetectionEngine(List<PiiDetector> detectors) {
        this.detectors = detectors == null ? List.of() : List.copyOf(detectors);
    }

    /**
     * Detects PII in the given text.
     *
     * @param text the text to scan, may be {@code null}
     * @return detections sorted by source position, never {@code null}
     */
    public List<PiiDetection> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiDetection> candidates = new ArrayList<>();
        for (PiiDetector detector : detectors) {
            List<PiiDetection> found = detector.detect(text);
            if (found != null) {
                candidates.addAll(found);
            }
        }

        candidates.sort(Comparator
                .comparingInt(PiiDetection::start)
                .thenComparing(detection -> detection.type().priority())
                .thenComparing(Comparator.comparingInt(PiiDetection::end).reversed()));

        List<PiiDetection> accepted = new ArrayList<>();
        for (PiiDetection candidate : candidates) {
            if (accepted.stream().noneMatch(kept -> overlaps(kept, candidate))) {
                accepted.add(candidate);
            }
        }

        accepted.sort(Comparator
                .comparingInt(PiiDetection::start)
                .thenComparingInt(PiiDetection::end)
                .thenComparing(detection -> detection.type().name()));
        return List.copyOf(accepted);
    }

    private boolean overlaps(PiiDetection kept, PiiDetection candidate) {
        return kept.start() < candidate.end() && candidate.start() < kept.end();
    }
}

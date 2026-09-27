package com.privacymask.masking;

import com.privacymask.detection.PiiDetection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites request text by replacing detected spans with their tokens.
 *
 * <p>Algorithm: sort spans by start offset, then walk the ORIGINAL string once
 * with a {@link StringBuilder}, copying untouched text verbatim and emitting the
 * token at each span. Length changes therefore never corrupt later positions -
 * no {@code String.replace()} anywhere. Overlapping spans that reach this layer
 * are skipped (first wins, consistent with detection ordering), so output is
 * never malformed. Punctuation and whitespace outside spans pass through
 * exactly. Runs in O(text + detections).</p>
 */
@Component
public class MaskingService {

    private final TokenizationService tokenizationService;

    public MaskingService(TokenizationService tokenizationService) {
        this.tokenizationService = tokenizationService;
    }

    /**
     * Masks the given text.
     *
     * @param originalText the original request text, may be {@code null}
     * @param detections engine detections for that text, may be {@code null}
     * @return masked text plus the in-memory mappings
     */
    public MaskingResult mask(String originalText, List<PiiDetection> detections) {
        if (originalText == null || originalText.isEmpty() || detections == null || detections.isEmpty()) {
            return new MaskingResult(originalText == null ? "" : originalText, List.of());
        }
        List<TokenMapping> mappings = tokenizationService.tokenize(detections);
        if (mappings.isEmpty()) {
            return new MaskingResult(originalText, List.of());
        }
        Map<String, String> tokenByKey = new HashMap<>();
        for (TokenMapping mapping : mappings) {
            tokenByKey.put(TokenKeys.keyOf(mapping.type(), mapping.originalValue()), mapping.token());
        }

        List<PiiDetection> ordered = new ArrayList<>(detections);
        ordered.sort(Comparator.comparingInt(PiiDetection::start));

        StringBuilder masked = new StringBuilder(originalText.length());
        int cursor = 0;
        for (PiiDetection detection : ordered) {
            if (!inBounds(originalText, detection) || detection.start() < cursor) {
                continue;
            }
            String token = tokenByKey.get(TokenKeys.keyOf(detection));
            if (token == null) {
                continue;
            }
            masked.append(originalText, cursor, detection.start()).append(token);
            cursor = detection.end();
        }
        masked.append(originalText, cursor, originalText.length());
        return new MaskingResult(masked.toString(), mappings);
    }

    private boolean inBounds(String text, PiiDetection detection) {
        return detection != null
                && detection.value() != null
                && detection.start() >= 0
                && detection.end() <= text.length()
                && detection.end() > detection.start();
    }
}

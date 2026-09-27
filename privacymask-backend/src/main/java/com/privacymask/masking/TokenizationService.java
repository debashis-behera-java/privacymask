package com.privacymask.masking;

import com.privacymask.detection.PiiDetection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns deterministic placeholder tokens to detected PII.
 *
 * <p>Numbering contract:</p>
 * <ul>
 *   <li>One global sequence per request, ordered by first occurrence in the
 *       original text - never grouped by type. E.g. email, phone, SSN become
 *       {@code {{EMAIL_001}}}, {@code {{PHONE_002}}}, {@code {{SSN_003}}}.</li>
 *   <li>Zero-padded to three digits: {@code {{TYPE_001}}}.</li>
 *   <li>Repeated values reuse the first token (see {@link TokenKeys} for the
 *       duplicate policy); no mapping is created twice.</li>
 * </ul>
 *
 * <p>Tokens contain only the type name and sequence number - never user content.
 * String manipulation is out of scope here; that belongs to
 * {@link MaskingService}.</p>
 */
@Component
public class TokenizationService {

    /**
     * Assigns tokens to the given detections.
     *
     * @param detections engine-ordered detections, may be {@code null}
     * @return one mapping per unique value, in first-occurrence order
     */
    public List<TokenMapping> tokenize(List<PiiDetection> detections) {
        if (detections == null || detections.isEmpty()) {
            return List.of();
        }
        List<PiiDetection> ordered = new ArrayList<>(detections);
        ordered.sort(Comparator.comparingInt(PiiDetection::start));

        Map<String, TokenMapping> unique = new LinkedHashMap<>();
        int cursor = 0;
        int sequence = 0;
        for (PiiDetection detection : ordered) {
            if (!hasValidSpan(detection)) {
                continue;
            }
            if (detection.start() < cursor) {
                // Overlapping lower-priority span that reached this layer:
                // skip it so numbering stays aligned with the masked output.
                continue;
            }
            cursor = detection.end();
            String key = TokenKeys.keyOf(detection);
            if (!unique.containsKey(key)) {
                sequence++;
                unique.put(key, new TokenMapping(
                        "{{" + detection.type().name() + "_" + String.format("%03d", sequence) + "}}",
                        detection.type(),
                        detection.value(),
                        detection.start(),
                        detection.end()));
            }
        }
        return List.copyOf(unique.values());
    }

    private boolean hasValidSpan(PiiDetection detection) {
        return detection != null
                && detection.type() != null
                && detection.value() != null
                && detection.start() >= 0
                && detection.end() > detection.start();
    }
}

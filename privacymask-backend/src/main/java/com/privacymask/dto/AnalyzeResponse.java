package com.privacymask.dto;

import com.privacymask.detection.PiiDetection;
import com.privacymask.model.ProcessingStatus;

import java.util.List;
import java.util.UUID;

/**
 * Gateway analyze response.
 *
 * <p>Phase 7: {@code processedText} is the masked text sent to the provider
 * (always stays tokenized); {@code response} is the provider reply with tokens
 * securely re-hydrated to original values.</p>
 *
 * <p>Demo caveat: this endpoint echoes {@code originalText} and detection values
 * for development visibility. That is NOT representative of the final production
 * gateway, where raw PII must not cross the privacy boundary. Token mappings are
 * never part of this response.</p>
 */
public record AnalyzeResponse(
        UUID requestId,
        String originalText,
        String processedText,
        String provider,
        ProcessingStatus status,
        List<PiiDetection> detections,
        String response) {

    public AnalyzeResponse {
        detections = detections == null ? List.of() : List.copyOf(detections);
    }
}

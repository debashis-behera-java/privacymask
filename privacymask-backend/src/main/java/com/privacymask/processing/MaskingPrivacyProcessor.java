package com.privacymask.processing;

import com.privacymask.detection.DetectionEngine;
import com.privacymask.detection.PiiDetection;
import com.privacymask.masking.MaskingResult;
import com.privacymask.masking.MaskingService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Active Phase 4 pipeline: detection, then tokenization, then masking.
 *
 * <p>A single detection pass feeds both the response's {@code detections} and
 * the masking step. Detection and masking are fast in-memory CPU work executed
 * inside the service's deferred pipeline (same pattern as previous phases), so
 * no artificial async wrapping is introduced. Nothing is encrypted or persisted
 * here - mappings live only for the request.</p>
 */
@Primary
@Component
public class MaskingPrivacyProcessor implements PrivacyProcessor {

    private final DetectionEngine detectionEngine;
    private final MaskingService maskingService;

    public MaskingPrivacyProcessor(DetectionEngine detectionEngine, MaskingService maskingService) {
        this.detectionEngine = detectionEngine;
        this.maskingService = maskingService;
    }

    @Override
    public Mono<PrivacyAnalysis> process(String text) {
        return Mono.defer(() -> {
            List<PiiDetection> detections = detectionEngine.detect(text);
            MaskingResult result = maskingService.mask(text, detections);
            return Mono.just(new PrivacyAnalysis(
                    result.maskedText(), detections, result.mappings()));
        });
    }
}

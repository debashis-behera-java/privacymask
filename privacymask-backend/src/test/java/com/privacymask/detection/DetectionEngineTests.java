package com.privacymask.detection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DetectionEngine} merging, ordering, and overlap
 * resolution, using small stub detectors so engine behavior is isolated from
 * any concrete pattern logic.
 */
class DetectionEngineTests {

    @Test
    void mergesDetectorsAndSortsByPosition() {
        PiiDetector late = text -> List.of(new PiiDetection(PiiType.PHONE, "p", 20, 25));
        PiiDetector early = text -> List.of(new PiiDetection(PiiType.EMAIL, "e", 5, 10));
        DetectionEngine engine = new DetectionEngine(List.of(late, early));

        List<PiiDetection> detections = engine.detect("irrelevant");

        assertThat(detections).extracting(PiiDetection::start).containsExactly(5, 20);
    }

    @Test
    void moreSpecificTypeWinsOverlap() {
        // PHONE candidate fully covers the SSN span; SSN must win by priority.
        PiiDetector phone = text -> List.of(new PiiDetection(PiiType.PHONE, "123-45-6789", 5, 16));
        PiiDetector ssn = text -> List.of(new PiiDetection(PiiType.SSN, "123-45-6789", 5, 16));
        DetectionEngine engine = new DetectionEngine(List.of(phone, ssn));

        List<PiiDetection> detections = engine.detect("irrelevant");

        assertThat(detections).hasSize(1);
        assertThat(detections.get(0).type()).isEqualTo(PiiType.SSN);
    }

    @Test
    void partialOverlapKeepsEarlierCandidate() {
        PiiDetector first = text -> List.of(new PiiDetection(PiiType.EMAIL, "a", 0, 10));
        PiiDetector second = text -> List.of(new PiiDetection(PiiType.URL, "b", 5, 15));
        DetectionEngine engine = new DetectionEngine(List.of(first, second));

        // Same dynamic cannot arise from real detectors (URL outranks EMAIL), but the
        // engine must still resolve it deterministically: earliest start wins.
        assertThat(engine.detect("irrelevant")).hasSize(1);
        assertThat(engine.detect("irrelevant").get(0).start()).isEqualTo(0);
    }

    @Test
    void exactDuplicateSpansAreReturnedOnce() {
        PiiDetection duplicate = new PiiDetection(PiiType.EMAIL, "e", 5, 10);
        DetectionEngine engine = new DetectionEngine(List.of(
                text -> List.of(duplicate),
                text -> List.of(duplicate)));

        assertThat(engine.detect("irrelevant")).containsExactly(duplicate);
    }

    @Test
    void resolutionIsDeterministic() {
        PiiDetector stub = text -> List.of(
                new PiiDetection(PiiType.PHONE, "p", 5, 16),
                new PiiDetection(PiiType.SSN, "s", 5, 16),
                new PiiDetection(PiiType.EMAIL, "e", 0, 4));
        DetectionEngine engine = new DetectionEngine(List.of(stub));

        assertThat(engine.detect("irrelevant")).isEqualTo(engine.detect("irrelevant"));
    }

    @Test
    void nullAndEmptyInputsYieldNoDetections() {
        DetectionEngine engine = new DetectionEngine(List.of(new RegexPiiDetector()));

        assertThat(engine.detect(null)).isEmpty();
        assertThat(engine.detect("")).isEmpty();
    }

    @Test
    void tolerantOfNullDetectorListAndNullResults() {
        assertThat(new DetectionEngine(null).detect("john@example.com")).isEmpty();

        PiiDetector nullReturning = text -> null;
        assertThat(new DetectionEngine(List.of(nullReturning)).detect("john@example.com")).isEmpty();
    }
}

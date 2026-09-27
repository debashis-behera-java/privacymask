package com.privacymask.detection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for regex PII detection, executed through the real
 * {@link DetectionEngine} wiring (single regex detector) so overlap resolution
 * and ordering are covered exactly as the API serves them.
 *
 * <p>Card tests use the standard Luhn-valid TEST number 4111 1111 1111 1111.</p>
 */
class RegexPiiDetectorTests {

    private final DetectionEngine engine = new DetectionEngine(List.of(new RegexPiiDetector()));

    // EMAIL

    @Test
    void simpleEmailIsDetected() {
        String text = "Contact john@example.com please";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(detections).hasSize(1);
        assertSound(text, detections.get(0), PiiType.EMAIL, "john@example.com");
    }

    @Test
    void dottedEmailIsDetected() {
        List<PiiDetection> detections = engine.detect("Email john.doe@example.com today");

        assertThat(detections).hasSize(1);
        assertThat(detections.get(0).type()).isEqualTo(PiiType.EMAIL);
        assertThat(detections.get(0).value()).isEqualTo("john.doe@example.com");
    }

    @Test
    void plusAddressEmailIsDetected() {
        List<PiiDetection> detections = engine.detect("Email user+support@example.co.uk today");

        assertThat(detections).hasSize(1);
        assertThat(detections.get(0).type()).isEqualTo(PiiType.EMAIL);
        assertThat(detections.get(0).value()).isEqualTo("user+support@example.co.uk");
    }

    @Test
    void malformedEmailIsNotDetected() {
        assertThat(engine.detect("Contact john@example please")).isEmpty();
        assertThat(engine.detect("Contact john@example.c please")).isEmpty();
    }

    // PHONE

    @Test
    void tenDigitPhoneIsDetected() {
        String text = "Call 9876543210 now";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(detections).hasSize(1);
        assertSound(text, detections.get(0), PiiType.PHONE, "9876543210");
    }

    @Test
    void internationalPhonesAreDetected() {
        assertThat(valuesOf(engine.detect("Call +91 9876543210 now")))
                .containsExactly("+91 9876543210");
        assertThat(valuesOf(engine.detect("Call +1 555 123 4567 now")))
                .containsExactly("+1 555 123 4567");
    }

    @Test
    void formattedPhonesAreDetected() {
        assertThat(valuesOf(engine.detect("Call (555) 123-4567 now")))
                .containsExactly("(555) 123-4567");
        assertThat(valuesOf(engine.detect("Call 555-123-4567 now")))
                .containsExactly("555-123-4567");
    }

    @Test
    void cleanTextHasNoPhone() {
        assertThat(engine.detect("Hello, I need help with my order.")).isEmpty();
    }

    // CREDIT_CARD

    @Test
    void contiguousTestCardIsDetected() {
        String text = "Card 4111111111111111 here";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(detections).hasSize(1);
        assertSound(text, detections.get(0), PiiType.CREDIT_CARD, "4111111111111111");
    }

    @Test
    void spacedTestCardIsDetected() {
        List<PiiDetection> detections = engine.detect("Card 4111 1111 1111 1111 here");

        assertThat(detections).hasSize(1);
        assertThat(detections.get(0).type()).isEqualTo(PiiType.CREDIT_CARD);
        assertThat(detections.get(0).value()).isEqualTo("4111 1111 1111 1111");
    }

    @Test
    void hyphenatedTestCardIsDetected() {
        List<PiiDetection> detections = engine.detect("Card 4111-1111-1111-1111 here");

        assertThat(detections).hasSize(1);
        assertThat(detections.get(0).type()).isEqualTo(PiiType.CREDIT_CARD);
        assertThat(detections.get(0).value()).isEqualTo("4111-1111-1111-1111");
    }

    @Test
    void invalidLuhnNumberIsNotDetected() {
        assertThat(engine.detect("Card 4111 1111 1111 1112 here")).isEmpty();
        assertThat(engine.detect("Number 1234567890123456 here")).isEmpty();
    }

    // SSN

    @Test
    void validSsnPatternIsDetected() {
        String text = "SSN: 123-45-6789.";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(detections).hasSize(1);
        assertSound(text, detections.get(0), PiiType.SSN, "123-45-6789");
    }

    @Test
    void malformedSsnIsNotDetectedAsSsn() {
        assertThat(engine.detect("ID 123456789 here")).isEmpty();
        assertThat(typesOf(engine.detect("ID 123-45-678 here"))).doesNotContain(PiiType.SSN);
    }

    // IP_ADDRESS

    @Test
    void validIpv4AddressesAreDetected() {
        assertThat(valuesOf(engine.detect("Server 192.168.1.10 is down")))
                .containsExactly("192.168.1.10");
        assertThat(valuesOf(engine.detect("Ping 8.8.8.8 now")))
                .containsExactly("8.8.8.8");
    }

    @Test
    void invalidIpv4IsNotDetected() {
        assertThat(engine.detect("Server 999.999.999.999 here")).isEmpty();
    }

    // URL

    @Test
    void httpsUrlIsDetected() {
        String text = "Visit https://example.com for info";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(detections).hasSize(1);
        assertSound(text, detections.get(0), PiiType.URL, "https://example.com");
    }

    @Test
    void httpUrlWithPathIsDetected() {
        List<PiiDetection> detections = engine.detect("See http://example.com/path here");

        assertThat(detections).hasSize(1);
        assertThat(detections.get(0).type()).isEqualTo(PiiType.URL);
        assertThat(detections.get(0).value()).isEqualTo("http://example.com/path");
    }

    @Test
    void urlWithQueryIsDetected() {
        List<PiiDetection> detections = engine.detect("See https://api.example.com/users?id=123 here");

        assertThat(detections).hasSize(1);
        assertThat(detections.get(0).value()).isEqualTo("https://api.example.com/users?id=123");
    }

    @Test
    void trailingSentencePunctuationIsExcludedFromUrl() {
        String text = "Visit https://example.com.";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(detections).hasSize(1);
        assertSound(text, detections.get(0), PiiType.URL, "https://example.com");
    }

    // MULTIPLE

    @Test
    void emailAndPhoneAreBothDetectedInOrder() {
        String text = "My email is john@example.com and my phone is +91 9876543210.";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(typesOf(detections)).containsExactly(PiiType.EMAIL, PiiType.PHONE);
        detections.forEach(detection -> assertSound(text, detection, detection.type(), detection.value()));
    }

    @Test
    void cardAndEmailAreBothDetected() {
        String text = "Card 4111 1111 1111 1111 belongs to john@example.com.";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(typesOf(detections)).containsExactly(PiiType.CREDIT_CARD, PiiType.EMAIL);
    }

    @Test
    void emailCardAndPhoneAreAllDetectedInOrder() {
        String text = "Mail john@example.com, card 4111-1111-1111-1111, call 9876543210.";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(typesOf(detections))
                .containsExactly(PiiType.EMAIL, PiiType.CREDIT_CARD, PiiType.PHONE);
    }

    @Test
    void multipleEmailsAreDetectedInOrder() {
        String text = "From a@example.com to b@example.org today";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(valuesOf(detections)).containsExactly("a@example.com", "b@example.org");
        assertThat(typesOf(detections)).containsExactly(PiiType.EMAIL, PiiType.EMAIL);
    }

    // CLEAN / POSITIONS / ORDER / DUPLICATES

    @Test
    void cleanTextHasNoDetections() {
        assertThat(engine.detect("Hello, I need help with my order.")).isEmpty();
    }

    @Test
    void offsetsReferToOriginalText() {
        String text = "Contact me at john@example.com or +91 9876543210.";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(detections).hasSize(2);
        PiiDetection email = detections.get(0);
        assertThat(email.type()).isEqualTo(PiiType.EMAIL);
        assertThat(email.start()).isEqualTo(text.indexOf("john@example.com"));
        assertThat(email.end()).isEqualTo(email.start() + "john@example.com".length());

        PiiDetection phone = detections.get(1);
        assertThat(phone.type()).isEqualTo(PiiType.PHONE);
        assertThat(phone.start()).isEqualTo(text.indexOf("+91 9876543210"));
        assertThat(phone.end()).isEqualTo(phone.start() + "+91 9876543210".length());
    }

    @Test
    void detectionsAreSortedBySourcePosition() {
        String text = "Email john@example.com and call 9876543210.";
        List<PiiDetection> detections = engine.detect(text);

        assertThat(typesOf(detections)).containsExactly(PiiType.EMAIL, PiiType.PHONE);
        assertThat(detections.get(0).start()).isLessThan(detections.get(1).start());
    }

    @Test
    void singleOccurrenceIsNotDuplicated() {
        assertThat(engine.detect("Mail john@example.com now")).hasSize(1);
    }

    @Test
    void detectorIsNullSafe() {
        PiiDetector detector = new RegexPiiDetector();
        assertThat(detector.detect(null)).isEmpty();
        assertThat(detector.detect("")).isEmpty();
    }

    private void assertSound(String text, PiiDetection detection, PiiType type, String value) {
        assertThat(detection.type()).isEqualTo(type);
        assertThat(detection.value()).isEqualTo(value);
        assertThat(detection.start()).isEqualTo(text.indexOf(value));
        assertThat(text.substring(detection.start(), detection.end())).isEqualTo(value);
    }

    private List<String> valuesOf(List<PiiDetection> detections) {
        return detections.stream().map(PiiDetection::value).toList();
    }

    private List<PiiType> typesOf(List<PiiDetection> detections) {
        return detections.stream().map(PiiDetection::type).toList();
    }
}

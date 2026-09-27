package com.privacymask.masking;

import com.privacymask.detection.DetectionEngine;
import com.privacymask.detection.PiiDetection;
import com.privacymask.detection.PiiType;
import com.privacymask.detection.RegexPiiDetector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for offset-based masking.
 *
 * <p>Most cases run through the real {@link DetectionEngine} wiring so masking
 * is verified against true detector output; overlap safety uses hand-crafted
 * detections aimed directly at the masking layer.</p>
 */
class MaskingServiceTests {

    private final DetectionEngine engine = new DetectionEngine(List.of(new RegexPiiDetector()));
    private final MaskingService maskingService = new MaskingService(new TokenizationService());

    @Test
    void singleEmailIsMasked() {
        assertThat(mask("My email is john@example.com."))
                .isEqualTo("My email is {{EMAIL_001}}.");
    }

    @Test
    void singlePhoneIsMasked() {
        assertThat(mask("Call +91 9876543210."))
                .isEqualTo("Call {{PHONE_001}}.");
    }

    @Test
    void creditCardIsMasked() {
        assertThat(mask("Card 4111 1111 1111 1111."))
                .isEqualTo("Card {{CREDIT_CARD_001}}.");
    }

    @Test
    void ssnIsMasked() {
        assertThat(mask("SSN 123-45-6789."))
                .isEqualTo("SSN {{SSN_001}}.");
    }

    @Test
    void ipAddressIsMasked() {
        assertThat(mask("Server 192.168.1.10 down."))
                .isEqualTo("Server {{IP_ADDRESS_001}} down.");
    }

    @Test
    void urlIsMasked() {
        assertThat(mask("Visit https://example.com."))
                .isEqualTo("Visit {{URL_001}}.");
    }

    @Test
    void multipleTypesShareGlobalNumbering() {
        assertThat(mask("Customer email is john@example.com, phone is +91 9876543210, SSN is 123-45-6789."))
                .isEqualTo("Customer email is {{EMAIL_001}}, phone is {{PHONE_002}}, SSN is {{SSN_003}}.");
    }

    @Test
    void multipleEmailsAreNumberedInOrder() {
        assertThat(mask("Emails: john@example.com and jane@example.com."))
                .isEqualTo("Emails: {{EMAIL_001}} and {{EMAIL_002}}.");
    }

    @Test
    void multiplePhonesAreNumberedInOrder() {
        assertThat(mask("Call 9876543210 or 555-123-4567."))
                .isEqualTo("Call {{PHONE_001}} or {{PHONE_002}}.");
    }

    @Test
    void duplicateEmailReusesToken() {
        MaskingResult result = maskResult("Email john@example.com. Confirm john@example.com.");

        assertThat(result.maskedText())
                .isEqualTo("Email {{EMAIL_001}}. Confirm {{EMAIL_001}}.");
        assertThat(result.mappings()).hasSize(1);
    }

    @Test
    void duplicatePhoneReusesToken() {
        assertThat(mask("Call 9876543210, again 9876543210."))
                .isEqualTo("Call {{PHONE_001}}, again {{PHONE_001}}.");
    }

    @Test
    void sentencePunctuationIsPreserved() {
        assertThat(mask("Email: john@example.com.")).isEqualTo("Email: {{EMAIL_001}}.");
        assertThat(mask("Call (555) 123-4567!")).isEqualTo("Call {{PHONE_001}}!");
    }

    @Test
    void whitespaceIsPreservedExactly() {
        assertThat(mask("Email   john@example.com"))
                .isEqualTo("Email   {{EMAIL_001}}");
        assertThat(mask("Email john@example.com\nPhone   9876543210"))
                .isEqualTo("Email {{EMAIL_001}}\nPhone   {{PHONE_002}}");
    }

    @Test
    void longReplacementDoesNotCorruptLaterPositions() {
        // The card (19 chars) becomes a shorter token; the phone after it must
        // still be replaced at its original offset.
        assertThat(mask("Card 4111 1111 1111 1111 call 9876543210 end."))
                .isEqualTo("Card {{CREDIT_CARD_001}} call {{PHONE_002}} end.");
    }

    @Test
    void overlappingDetectionsDoNotCorruptOutput() {
        String text = "SSN 123-45-6789 here";
        List<PiiDetection> overlapping = List.of(
                new PiiDetection(PiiType.SSN, "123-45-6789", 4, 15),
                new PiiDetection(PiiType.PHONE, "123-45-6789", 4, 15));

        MaskingResult result = maskingService.mask(text, overlapping);

        assertThat(result.maskedText()).isEqualTo("SSN {{SSN_001}} here");
        assertThat(result.mappings()).hasSize(1);
    }

    @Test
    void cleanTextIsUnchangedWithNoMappings() {
        MaskingResult result = maskResult("Hello, I need help with my order.");

        assertThat(result.maskedText()).isEqualTo("Hello, I need help with my order.");
        assertThat(result.mappings()).isEmpty();
    }

    @Test
    void nullSafe() {
        assertThat(maskingService.mask(null, null).maskedText()).isEqualTo("");
        assertThat(maskingService.mask(null, null).mappings()).isEmpty();
        assertThat(maskingService.mask("text", null).maskedText()).isEqualTo("text");
    }

    private String mask(String text) {
        return maskResult(text).maskedText();
    }

    private MaskingResult maskResult(String text) {
        return maskingService.mask(text, engine.detect(text));
    }
}

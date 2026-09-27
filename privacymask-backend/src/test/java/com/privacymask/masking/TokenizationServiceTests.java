package com.privacymask.masking;

import com.privacymask.detection.DetectionEngine;
import com.privacymask.detection.PiiDetection;
import com.privacymask.detection.PiiType;
import com.privacymask.detection.RegexPiiDetector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for deterministic token assignment.
 *
 * <p>Numbering follows one global per-request sequence in first-occurrence
 * order (see class-level contract on {@link TokenizationService}).</p>
 */
class TokenizationServiceTests {

    private final TokenizationService tokenizationService = new TokenizationService();
    private final DetectionEngine engine = new DetectionEngine(List.of(new RegexPiiDetector()));

    @Test
    void firstEmailGetsFirstToken() {
        List<TokenMapping> mappings = tokenize("Email john@example.com.");

        assertThat(mappings).hasSize(1);
        TokenMapping mapping = mappings.get(0);
        assertThat(mapping.token()).isEqualTo("{{EMAIL_001}}");
        assertThat(mapping.type()).isEqualTo(PiiType.EMAIL);
        assertThat(mapping.originalValue()).isEqualTo("john@example.com");
        assertThat(mapping.start()).isEqualTo("Email john@example.com.".indexOf("john@example.com"));
        assertThat(mapping.end()).isEqualTo(mapping.start() + "john@example.com".length());
    }

    @Test
    void secondDistinctEmailGetsSecondToken() {
        List<TokenMapping> mappings = tokenize("Emails: john@example.com and jane@example.com.");

        assertThat(mappings).extracting(TokenMapping::token)
                .containsExactly("{{EMAIL_001}}", "{{EMAIL_002}}");
    }

    @Test
    void differentTypesShareOneGlobalSequence() {
        List<TokenMapping> mappings = tokenize(
                "Customer email is john@example.com, phone is +91 9876543210, SSN is 123-45-6789.");

        assertThat(mappings).extracting(TokenMapping::token)
                .containsExactly("{{EMAIL_001}}", "{{PHONE_002}}", "{{SSN_003}}");
    }

    @Test
    void numberingFollowsFirstOccurrenceAcrossTypes() {
        List<TokenMapping> mappings = tokenize("Call 9876543210 or email john@example.com.");

        assertThat(mappings).extracting(TokenMapping::token)
                .containsExactly("{{PHONE_001}}", "{{EMAIL_002}}");
    }

    @Test
    void sameEmailTwiceProducesOneMapping() {
        List<TokenMapping> mappings = tokenize("Email john@example.com. Confirm john@example.com.");

        assertThat(mappings).hasSize(1);
        assertThat(mappings.get(0).token()).isEqualTo("{{EMAIL_001}}");
    }

    @Test
    void emailDuplicatesMatchCaseInsensitively() {
        List<TokenMapping> mappings = tokenize("Email john@example.com, also JOHN@EXAMPLE.COM.");

        assertThat(mappings).hasSize(1);
        assertThat(mappings.get(0).token()).isEqualTo("{{EMAIL_001}}");
        // The stored value is the exact first-seen occurrence, never normalized.
        assertThat(mappings.get(0).originalValue()).isEqualTo("john@example.com");
    }

    @Test
    void samePhoneTwiceProducesOneMapping() {
        List<TokenMapping> mappings = tokenize("Call 9876543210, again 9876543210.");

        assertThat(mappings).hasSize(1);
        assertThat(mappings.get(0).token()).isEqualTo("{{PHONE_001}}");
    }

    @Test
    void tokensMatchRequiredFormat() {
        List<TokenMapping> mappings = tokenize(
                "Mail john@example.com, card 4111-1111-1111-1111, call 9876543210.");

        assertThat(mappings).extracting(TokenMapping::token).allSatisfy(token ->
                assertThat(token).matches("^\\{\\{[A-Z_]+_\\d{3}\\}\\}$"));
    }

    @Test
    void cleanTextProducesNoMappings() {
        assertThat(tokenize("Hello, I need help with my order.")).isEmpty();
    }

    @Test
    void nullAndEmptyInputsProduceNoMappings() {
        assertThat(tokenizationService.tokenize(null)).isEmpty();
        assertThat(tokenizationService.tokenize(List.of())).isEmpty();
    }

    @Test
    void overlappingDetectionsYieldSingleMapping() {
        String text = "SSN 123-45-6789 here";
        PiiDetection ssn = new PiiDetection(PiiType.SSN, "123-45-6789", 4, 15);
        PiiDetection phone = new PiiDetection(PiiType.PHONE, "123-45-6789", 4, 15);

        List<TokenMapping> mappings = tokenizationService.tokenize(List.of(ssn, phone));

        assertThat(mappings).hasSize(1);
        assertThat(mappings.get(0).token()).isEqualTo("{{SSN_001}}");
    }

    private List<TokenMapping> tokenize(String text) {
        return tokenizationService.tokenize(engine.detect(text));
    }
}

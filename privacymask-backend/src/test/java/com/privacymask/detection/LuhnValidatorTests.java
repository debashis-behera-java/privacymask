package com.privacymask.detection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Luhn checksum gate used by card detection.
 *
 * <p>Uses the standard Luhn-valid TEST card number 4111111111111111 (and its
 * spaced/hyphenated forms) - never a real card number.</p>
 */
class LuhnValidatorTests {

    @Test
    void validContiguousTestCardPasses() {
        assertThat(LuhnValidator.isValid("4111111111111111")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111 1111 1111 1111", "4111-1111-1111-1111", "4111111111111111", "378282246310005"})
    void validCandidatesPass(String candidate) {
        assertThat(LuhnValidator.isValidCandidate(candidate)).isTrue();
    }

    @Test
    void singleDigitChangeFails() {
        assertThat(LuhnValidator.isValid("4111111111111112")).isFalse();
        assertThat(LuhnValidator.isValidCandidate("4111 1111 1111 1112")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123", "123456789012", "12345678901234567890", "411111111111111a", "0000000000000000"})
    void implausibleInputsFail(String input) {
        assertThat(LuhnValidator.isValid(input)).isFalse();
    }

    @Test
    void nullInputsFail() {
        assertThat(LuhnValidator.isValid(null)).isFalse();
        assertThat(LuhnValidator.isValidCandidate(null)).isFalse();
    }

    @Test
    void unexpectedSeparatorFails() {
        assertThat(LuhnValidator.isValidCandidate("4111/1111/1111/1111")).isFalse();
    }
}

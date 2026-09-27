package com.privacymask.request;

import com.privacymask.config.PrivacyMaskProperties;
import com.privacymask.exception.RequestTooLargeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for deterministic size enforcement (ASCII, Unicode, emoji) and
 * boundary behavior. Length is Java characters (UTF-16 code units).
 */
class RequestSizeValidatorTests {

    private static final int LIMIT = 100;

    private final RequestSizeValidator validator = new RequestSizeValidator(
            new PrivacyMaskProperties(null, null, null,
                    new PrivacyMaskProperties.Request(LIMIT), null));

    @Test
    void belowLimitPasses() {
        assertThatCode(() -> validator.validate("a".repeat(LIMIT - 1))).doesNotThrowAnyException();
    }

    @Test
    void exactlyAtLimitPasses() {
        assertThatCode(() -> validator.validate("a".repeat(LIMIT))).doesNotThrowAnyException();
    }

    @Test
    void aboveLimitFails() {
        assertThatThrownBy(() -> validator.validate("a".repeat(LIMIT + 1)))
                .isInstanceOf(RequestTooLargeException.class)
                .hasMessageContaining(String.valueOf(LIMIT));
    }

    @Test
    void unicodeCountsDeterministically() {
        // BMP characters count one unit each.
        assertThatCode(() -> validator.validate("ग्राहक".repeat(10))).doesNotThrowAnyException();
    }

    @Test
    void emojiCountsAsTwoUnits() {
        String emoji = "😀";
        // Sanity: one emoji is two UTF-16 code units.
        org.assertj.core.api.Assertions.assertThat(emoji.length()).isEqualTo(2);
        assertThatCode(() -> validator.validate(emoji.repeat(LIMIT / 2))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(emoji.repeat(LIMIT / 2 + 1)))
                .isInstanceOf(RequestTooLargeException.class);
    }

    @Test
    void mixedContentCountsDeterministically() {
        String mixed = "aग्रा😀";
        org.assertj.core.api.Assertions.assertThat(mixed.length()).isEqualTo(1 + 4 + 2);
        assertThatCode(() -> validator.validate(mixed)).doesNotThrowAnyException();
    }

    @Test
    void nullIsTolerated() {
        // Null/blank rejection belongs to bean validation, which runs earlier.
        assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
    }
}

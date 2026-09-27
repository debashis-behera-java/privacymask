package com.privacymask.ratelimit;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate-limit configuration: defaults load, presence reporting stays
 * secret-free, and invalid values are constraint violations (fail-closed at
 * startup, never silent unlimited mode).
 */
@SpringBootTest
class RateLimitPropertiesTests {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Autowired
    private RateLimitProperties properties;

    @Test
    void defaultsAreEnabledWithDocumentedCapacity() {
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.capacity()).isEqualTo(120);
        assertThat(properties.refillPerMinute()).isEqualTo(120);
        assertThat(properties.isConfigured()).isTrue();
    }

    @Test
    void disabledReportsUnconfiguredWithoutSecrets() {
        assertThat(new RateLimitProperties(false, 120, 120).isConfigured()).isFalse();
        assertThat(VALIDATOR.validate(new RateLimitProperties(false, 120, 120))).isEmpty();
    }

    @Test
    void zeroCapacityIsAConstraintViolation() {
        assertThat(VALIDATOR.validate(new RateLimitProperties(true, 0, 60))).isNotEmpty();
    }

    @Test
    void zeroRefillIsAConstraintViolation() {
        assertThat(VALIDATOR.validate(new RateLimitProperties(true, 60, 0))).isNotEmpty();
    }

    @Test
    void negativeValuesAreConstraintViolations() {
        assertThat(VALIDATOR.validate(new RateLimitProperties(true, -5, -10))).isNotEmpty();
    }

    @Test
    void validConfigurationHasNoViolations() {
        assertThat(VALIDATOR.validate(new RateLimitProperties(true, 3, 1))).isEmpty();
    }
}

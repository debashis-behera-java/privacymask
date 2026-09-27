package com.privacymask.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for fail-safe CORS origin handling: only explicit origins are
 * ever honored. A wildcard ({@code *}) must never open the API to every
 * website, even if an operator misconfigures it.
 */
class CorsPropertiesTests {

    @Test
    void nullOriginsStayDisabled() {
        assertThat(new CorsProperties(null).origins()).isEmpty();
    }

    @Test
    void blankOriginsStayDisabled() {
        assertThat(new CorsProperties(List.of("", "   ")).origins()).isEmpty();
    }

    @Test
    void wildcardOriginIsDropped() {
        assertThat(new CorsProperties(List.of("*")).origins()).isEmpty();
    }

    @Test
    void wildcardAmongValidOriginsIsDroppedOnly() {
        assertThat(new CorsProperties(List.of("http://localhost:5173", "*")).origins())
                .containsExactly("http://localhost:5173");
    }

    @Test
    void exactOriginsArePreservedAndTrimmed() {
        assertThat(new CorsProperties(
                List.of("  http://localhost:5173 ", "https://app.example.com")).origins())
                .containsExactly("http://localhost:5173", "https://app.example.com");
    }
}

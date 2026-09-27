package com.privacymask.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Central configuration for PrivacyMask.
 *
 * <p>{@code encryption.key} is a Base64-encoded 32-byte AES key, provided only
 * via configuration (e.g. the {@code PRIVACYMASK_ENCRYPTION_KEY} environment
 * variable). It intentionally has no usable default: encryption fails clearly
 * when invoked without a valid key instead of silently using a weak or
 * ephemeral one. {@code mapping.ttl} bounds the in-memory encrypted-mapping
 * lifetime (persistent storage arrives in a later phase).</p>
 *
 * <p>{@code request.max-text-length} caps accepted input (Java characters, i.e.
 * UTF-16 code units - deterministic for ASCII, BMP Unicode, and surrogate-pair
 * emoji alike) before any detection work runs. {@code provider.timeout} bounds
 * every provider call reactively; invalid values fail fast at startup via
 * binding validation.</p>
 *
 * <p>All values are overridable via environment variables, e.g.
 * {@code PRIVACYMASK_APP_VERSION} maps to {@code privacymask.app.version}.</p>
 */
@Validated
@ConfigurationProperties(prefix = "privacymask")
public record PrivacyMaskProperties(
        @Valid App app,
        @Valid Encryption encryption,
        @Valid Mapping mapping,
        @Valid Request request,
        @Valid Provider provider) {

    public PrivacyMaskProperties {
        if (app == null) {
            app = new App("PrivacyMask", "0.1.0");
        }
        if (encryption == null) {
            encryption = new Encryption("");
        }
        if (mapping == null) {
            mapping = new Mapping(Duration.ofMinutes(10));
        }
        if (request == null) {
            request = new Request(10_000);
        }
        if (provider == null) {
            provider = new Provider(Duration.ofSeconds(10));
        }
    }

    public record App(
            @NotBlank String name,
            @NotBlank String version) {
    }

    public record Encryption(
            @NotNull String key) {
    }

    public record Mapping(
            @NotNull Duration ttl) {
    }

    public record Request(
            @Min(1) int maxTextLength) {
    }

    public record Provider(
            @NotNull Duration timeout) {
    }
}

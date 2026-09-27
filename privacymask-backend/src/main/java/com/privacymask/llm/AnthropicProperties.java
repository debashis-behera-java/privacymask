package com.privacymask.llm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Dedicated Anthropic provider configuration.
 *
 * <p>All values are environment-overridable; nothing sensitive has a usable
 * default. The application starts without an API key - selecting the
 * {@code anthropic} provider then fails safely at request time instead. The key
 * never appears in logs, errors, responses, or status output (it is only ever
 * placed on the outbound {@code x-api-key} header).</p>
 *
 * <p>{@code baseUrl} and {@code model} are deliberately defaults, not
 * hard-coded business logic: environments keep the URL and model up to date
 * without code changes, and tests override both freely. {@code apiVersion}
 * localizes the Anthropic Messages API version header so future version bumps
 * are a configuration change, not a source scatter.</p>
 */
@Validated
@ConfigurationProperties(prefix = "privacymask.providers.anthropic")
public record AnthropicProperties(
        @NotBlank String baseUrl,
        @NotNull String apiKey,
        @NotBlank String model,
        @NotBlank String apiVersion) {

    public AnthropicProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.anthropic.com";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (model == null || model.isBlank()) {
            // Configured default only - never assume a specific Claude model
            // will remain available indefinitely.
            model = "claude-sonnet-4-20250514";
        }
        if (apiVersion == null || apiVersion.isBlank()) {
            apiVersion = "2023-06-01";
        }
    }

    /**
     * Whether a key is present. Presence only - never the key itself.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
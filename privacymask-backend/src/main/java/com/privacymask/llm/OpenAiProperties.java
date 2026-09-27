package com.privacymask.llm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Dedicated OpenAI provider configuration.
 *
 * <p>All values are environment-overridable; nothing sensitive has a usable
 * default. The application starts without an API key - selecting the
 * {@code openai} provider then fails safely at request time instead. The key
 * never appears in logs, errors, responses, or actuator output (it is only
 * ever placed on the outbound {@code Authorization} header).</p>
 */
@Validated
@ConfigurationProperties(prefix = "privacymask.providers.openai")
public record OpenAiProperties(
        @NotBlank String baseUrl,
        @NotNull String apiKey,
        @NotBlank String model) {

    public OpenAiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini";
        }
    }

    /**
     * Whether a key is present. Presence only - never the key itself.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}

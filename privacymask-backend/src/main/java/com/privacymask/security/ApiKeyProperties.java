package com.privacymask.security;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Dedicated API-key authentication configuration.
 *
 * <p>The credential is environment-overridable via {@code PRIVACYMASK_API_KEY}
 * and intentionally has no usable default: the application starts without a key,
 * and protected endpoints then fail safely with HTTP 401 at request time. The key
 * is never logged, never returned, never reflected, and never sent to any LLM
 * provider. It is compared using constant-time equality to avoid timing leaks.</p>
 *
 * <p>{@code isConfigured()} reports presence only — never the key itself. This
 * is consumed by the status endpoint to expose {@code securityConfigured} as a
 * boolean.</p>
 */
@Validated
@ConfigurationProperties(prefix = "privacymask.security")
public record ApiKeyProperties(@NotNull String apiKey) {

    public ApiKeyProperties {
        if (apiKey == null) {
            apiKey = "";
        }
    }

    /**
     * Whether a key is present. Presence only - never the key itself.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}

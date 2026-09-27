package com.privacymask.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Browser CORS configuration for the PrivacyMask gateway.
 *
 * <p>The API is a service-to-service gateway and ships with CORS effectively
 * disabled: when no origins are configured, no CORS headers are emitted and
 * browsers cannot call the API cross-origin. Local frontend development
 * (Vite dev server) opts in explicitly via
 * {@code PRIVACYMASK_CORS_ALLOWED_ORIGINS}, a comma-separated list of exact
 * origins (e.g. {@code http://localhost:5173}). A wildcard ({@code *}) is
 * dropped by {@link #origins()} rather than honored, credentials are never allowed, and only the gateway's own
 * headers/methods are listed. Authentication and rate limiting apply
 * identically with or without CORS.</p>
 */
@ConfigurationProperties(prefix = "privacymask.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        if (allowedOrigins == null) {
            allowedOrigins = List.of();
        }
    }

    /**
     * Configured origins with blanks removed. Empty means CORS stays off.
     *
     * <p>Fail-safe: a wildcard ({@code *}) is never a valid exact origin, so
     * it is dropped rather than honored. Without this, an operator typo would
     * silently open the API to every website. CORS therefore stays disabled
     * unless at least one explicit origin is configured.</p>
     */
    public List<String> origins() {
        return allowedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .filter(origin -> !origin.equals("*"))
                .toList();
    }
}

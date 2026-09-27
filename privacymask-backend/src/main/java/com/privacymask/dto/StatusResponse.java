package com.privacymask.dto;

/**
 * Public status payload for {@code GET /api/v1/privacymask/status}.
 *
 * <p>Immutable DTO - internal configuration objects are never exposed directly.
 * {@code openaiConfigured}, {@code anthropicConfigured},
 * {@code securityConfigured}, and {@code rateLimitConfigured} report
 * presence/state only (never key material, bucket internals, or client
 * state) and involve no external call.</p>
 */
public record StatusResponse(
        String application,
        String status,
        String version,
        boolean openaiConfigured,
        boolean anthropicConfigured,
        boolean securityConfigured,
        boolean rateLimitConfigured) {
}

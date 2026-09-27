package com.privacymask.ratelimit;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Rate-limiting configuration for the PrivacyMask gateway.
 *
 * <p>Token-bucket abuse protection on the protected analyze endpoint. The
 * limiter runs <em>after</em> API-key authentication and <em>before</em> any
 * PII processing, so unauthenticated requests can never consume quota and
 * rate-limited requests never reach detection, masking, encryption, or any
 * LLM provider.</p>
 *
 * <p>Environment overrides:</p>
 * <ul>
 *   <li>{@code PRIVACYMASK_RATE_LIMIT_ENABLED} (default {@code true})</li>
 *   <li>{@code PRIVACYMASK_RATE_LIMIT_CAPACITY} (default {@code 120} tokens)</li>
 *   <li>{@code PRIVACYMASK_RATE_LIMIT_REFILL_PER_MINUTE} (default {@code 120})</li>
 * </ul>
 *
 * <p>The defaults are development/test oriented: generous enough that the
 * pre-existing integration suite never trips the limiter, while still
 * bounding bursts. Production deployments should lower them via the
 * environment. Setting {@code enabled=false} bypasses rate limiting only;
 * authentication stays fully enforced.</p>
 *
 * <p>Invalid values ({@code capacity <= 0}, {@code refill <= 0}) fail
 * application startup via bean validation. An invalid security-related
 * configuration must never be silently reinterpreted as unlimited access.</p>
 *
 * <p>Phase 12 is intentionally single-instance and in-memory: no Redis, no
 * database, no distributed state. A horizontally scaled deployment would need
 * a shared limiter (e.g. Redis or gateway-level limiting).</p>
 */
@Validated
@ConfigurationProperties(prefix = "privacymask.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Min(value = 1, message = "rate-limit capacity must be at least 1") int capacity,
        @Min(value = 1, message = "rate-limit refill-per-minute must be at least 1") int refillPerMinute) {

    public RateLimitProperties {
        // No clamping here: invalid values must surface as constraint
        // violations (fail-closed at startup), never as silent unlimited mode.
    }

    /**
     * Whether the limiter should enforce decisions. Presence only — never key
     * material. Consumed by the status endpoint as {@code rateLimitConfigured}.
     */
    public boolean isConfigured() {
        return enabled;
    }
}

package com.privacymask.ratelimit;

/**
 * Outcome of a single rate-limit check for one authenticated client.
 *
 * @param allowed            whether the request may proceed to PrivacyMask
 *                           processing. A token is consumed exactly when
 *                           {@code allowed} is {@code true} (admission
 *                           accounting); denied requests consume nothing and
 *                           trigger no downstream work.
 * @param retryAfterSeconds  when {@code allowed} is {@code false}, a
 *                           conservative whole-second estimate until the next
 *                           token refills (suitable for a {@code Retry-After}
 *                           header); {@code 0} when allowed.
 */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    public static RateLimitDecision admit() {
        return new RateLimitDecision(true, 0);
    }

    public static RateLimitDecision reject(long retryAfterSeconds) {
        return new RateLimitDecision(false, Math.max(1, retryAfterSeconds));
    }
}

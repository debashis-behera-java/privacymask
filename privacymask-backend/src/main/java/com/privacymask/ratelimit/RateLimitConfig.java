package com.privacymask.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the Phase 12 rate-limiting collaborators.
 *
 * <p>{@link TokenBucketRateLimiter} is deliberately a plain injectable
 * object (not itself annotated) so unit tests can construct it directly
 * with arbitrary properties and a controllable {@link Clock}, without any
 * Spring context.</p>
 */
@Configuration
public class RateLimitConfig {

    /**
     * System time source for token-bucket refill. Exposed as a bean so tests
     * can substitute a fixed/controllable clock at the integration level and
     * so production behavior stays explicit.
     */
    @Bean
    public Clock rateLimitClock() {
        return Clock.systemUTC();
    }

    @Bean
    public TokenBucketRateLimiter tokenBucketRateLimiter(RateLimitProperties properties, Clock rateLimitClock) {
        return new TokenBucketRateLimiter(properties, rateLimitClock);
    }
}

package com.privacymask;

import com.privacymask.config.PrivacyMaskProperties;
import com.privacymask.llm.AnthropicProperties;
import com.privacymask.llm.OpenAiProperties;
import com.privacymask.ratelimit.RateLimitProperties;
import com.privacymask.security.ApiKeyProperties;
import com.privacymask.security.CorsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * PrivacyMask backend entry point.
 *
 * <p>Phase 1 establishes the reactive foundation only: a health/status API,
 * centralized error handling, and configuration namespaces for future phases
 * (detection, masking, encryption, LLM adapters, policy, mapping).</p>
 *
 * <p>Phase 11 excludes {@link ReactiveUserDetailsServiceAutoConfiguration}:
 * the gateway authenticates service clients with an API key only, so the
 * default in-memory user (and its generated password log line) is unwanted
 * surface. No form login, no Basic Auth, no user store.</p>
 */
@SpringBootApplication(exclude = ReactiveUserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties({
        PrivacyMaskProperties.class,
        OpenAiProperties.class,
        AnthropicProperties.class,
        ApiKeyProperties.class,
        CorsProperties.class,
        RateLimitProperties.class})
public class PrivacyMaskApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrivacyMaskApplication.class, args);
    }
}

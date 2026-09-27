package com.privacymask.security;

import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Validates the provided API key against the configured credential using
 * constant-time comparison. Never logs either value. If no key is configured,
 * every request fails authentication (fail-closed).
 */
public class ApiKeyAuthManager implements ReactiveAuthenticationManager {

    private final ApiKeyProperties apiKeyProperties;

    public ApiKeyAuthManager(ApiKeyProperties apiKeyProperties) {
        this.apiKeyProperties = apiKeyProperties;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        if (!apiKeyProperties.isConfigured()) {
            return Mono.error(new ApiKeyAuthenticationException("No API key is configured."));
        }
        String provided = (String) authentication.getPrincipal();
        String expected = apiKeyProperties.apiKey();
        if (constantTimeEquals(provided, expected)) {
            return Mono.just(new UsernamePasswordAuthenticationToken(provided, null, List.of()));
        }
        return Mono.error(new ApiKeyAuthenticationException("Invalid API key."));
    }

    /**
     * Constant-time byte comparison to avoid timing side-channels.
     * Neither value is logged.
     */
    private static boolean constantTimeEquals(String provided, String expected) {
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(providedBytes, expectedBytes);
    }
}

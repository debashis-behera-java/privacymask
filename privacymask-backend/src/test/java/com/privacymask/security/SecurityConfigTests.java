package com.privacymask.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the security configuration loads correctly and reports its
 * configured/unconfigured state without exposing key material.
 */
@SpringBootTest
class SecurityConfigTests {

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    @Test
    void securityPropertiesLoadFromConfiguration() {
        assertThat(apiKeyProperties).isNotNull();
        // The test application.yml sets a dummy key.
        assertThat(apiKeyProperties.isConfigured()).isTrue();
    }

    @Test
    void isConfiguredReportsPresenceOnly() {
        assertThat(new ApiKeyProperties("").isConfigured()).isFalse();
        assertThat(new ApiKeyProperties("   ").isConfigured()).isFalse();
        assertThat(new ApiKeyProperties(null).isConfigured()).isFalse();
        assertThat(new ApiKeyProperties("some-key").isConfigured()).isTrue();
    }

    @Test
    void nullApiKeyFallsBackToEmpty() {
        ApiKeyProperties properties = new ApiKeyProperties(null);
        assertThat(properties.apiKey()).isEqualTo("");
        assertThat(properties.isConfigured()).isFalse();
    }
}

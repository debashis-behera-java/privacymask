package com.privacymask.anthropic;

import com.privacymask.llm.AnthropicProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anthropic provider configuration: lenient defaults that keep the application
 * startable without a key, environment-style overrides, and presence-only key
 * reporting (never the key itself).
 */
class AnthropicPropertiesTests {

    @Test
    void defaultsKeepEveryValueUsableWhenBlanksAreBound() {
        AnthropicProperties properties = new AnthropicProperties(null, null, null, null);

        assertThat(properties.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(properties.apiKey()).isEqualTo("");
        assertThat(properties.model()).isNotBlank();
        assertThat(properties.apiVersion()).isNotBlank();
    }

    @Test
    void explicitValuesArePreserved() {
        AnthropicProperties properties =
                new AnthropicProperties("http://127.0.0.1:1", "dummy-key", "my-model", "2024-01-01");

        assertThat(properties.baseUrl()).isEqualTo("http://127.0.0.1:1");
        assertThat(properties.apiKey()).isEqualTo("dummy-key");
        assertThat(properties.model()).isEqualTo("my-model");
        assertThat(properties.apiVersion()).isEqualTo("2024-01-01");
    }

    @Test
    void isConfiguredReportsKeyPresenceOnly() {
        assertThat(new AnthropicProperties("u", "", "m", "v").isConfigured()).isFalse();
        assertThat(new AnthropicProperties("u", "   ", "m", "v").isConfigured()).isFalse();
        assertThat(new AnthropicProperties("u", "sk-ant-test", "m", "v").isConfigured()).isTrue();
    }
}
package com.privacymask.llm;

import com.privacymask.exception.UnsupportedProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for provider resolution and the mock provider contract.
 */
class LlmProviderRegistryTests {

    private final MockLlmProvider mock = new MockLlmProvider();
    private final LlmProviderRegistry registry = new LlmProviderRegistry(List.of(mock));

    @Test
    void mockProviderIsResolvable() {
        assertThat(registry.resolve("mock")).isSameAs(mock);
    }

    @Test
    void resolutionIsCaseInsensitive() {
        assertThat(registry.resolve("MOCK")).isSameAs(mock);
    }

    @Test
    void unsupportedProviderIsRejected() {
        assertThatThrownBy(() -> registry.resolve("xyz"))
                .isInstanceOf(UnsupportedProviderException.class)
                .hasMessageContaining("xyz");
        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(UnsupportedProviderException.class);
    }

    @Test
    void mockProviderReturnsDeterministicResponse() {
        UUID requestId = UUID.randomUUID();
        LlmRequest request = new LlmRequest(
                requestId, "mock", SanitizedText.masked("Hello {{EMAIL_001}}.", requestId));

        LlmResponse first = mock.complete(request).block();
        LlmResponse second = mock.complete(request).block();

        assertThat(first.provider()).isEqualTo("mock");
        assertThat(first.requestId()).isEqualTo(requestId);
        assertThat(first.responseText()).isNotBlank();
        assertThat(second.responseText()).isEqualTo(first.responseText());
    }
}

package com.privacymask.llm;

import com.privacymask.exception.UnsupportedProviderException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves provider names to {@link LlmProvider} beans.
 *
 * <p>Selection lives here - not in the controller, and not as scattered
 * if/else blocks. New vendors register by adding a bean; unknown names fail
 * with {@link UnsupportedProviderException} (the existing controlled 400).
 * Matching is case-insensitive and resolved per request against the live beans
 * (a linear scan is deliberate: provider lists are tiny, and lazy resolution
 * keeps test doubles and proxies working without construction-time coupling).
 * Nameless providers can never be selected.</p>
 */
@Component
public class LlmProviderRegistry {

    private final List<LlmProvider> providers;

    public LlmProviderRegistry(List<LlmProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /**
     * Returns the provider for the requested name.
     *
     * @throws UnsupportedProviderException for unknown names
     */
    public LlmProvider resolve(String name) {
        if (name != null) {
            String requested = name.trim();
            for (LlmProvider provider : providers) {
                if (provider != null
                        && provider.name() != null
                        && provider.name().trim().equalsIgnoreCase(requested)) {
                    return provider;
                }
            }
        }
        throw new UnsupportedProviderException(name);
    }
}

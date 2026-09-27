package com.privacymask.llm;

import java.util.UUID;

/**
 * Provider-neutral LLM response.
 *
 * <p>Deliberately free of vendor concepts (no OpenAI/Anthropic structures in
 * the core domain). Tokens are NOT re-hydrated in Phase 6 - if the provider
 * echoes a token such as {@code {{EMAIL_001}}}, it stays a token.</p>
 */
public record LlmResponse(UUID requestId, String provider, String responseText) {
}

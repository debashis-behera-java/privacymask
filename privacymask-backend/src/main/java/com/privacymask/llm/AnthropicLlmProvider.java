package com.privacymask.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.privacymask.exception.LlmProviderException;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Real Anthropic provider behind the privacy boundary, using the Anthropic
 * Messages API ({@code POST {base-url}/v1/messages}) over a non-blocking
 * {@link WebClient} built once and shared.
 *
 * <p>Boundary enforcement (structural, not conventional): the outbound
 * {@code content} is the {@link SanitizedText} value only - the request type
 * cannot carry anything else, so raw PII, plaintext/encrypted mappings, AES
 * keys, and detector internals cannot reach Anthropic. No system prompt is
 * sent (the Messages API treats it as optional); if one is ever required it
 * must stay minimal and provider-neutral, with no PII, mapping, or key
 * material. The key travels solely on the {@code x-api-key} header (the
 * {@code anthropic-version} header rides alongside) from configuration; a
 * missing key fails here with no HTTP attempt, no fallback, no retry.
 * Credentials are never placed in URLs, JSON bodies, or logs.</p>
 *
 * <p>Responses are parsed into the provider-neutral {@link LlmResponse}; only
 * the text of {@code content} blocks is kept. Empty/malformed payloads fail
 * closed - the masked request is never returned as the answer; if Anthropic
 * ever echoed raw PII no mapping would be created or altered (re-hydration
 * only restores tokens the gateway already knows). Upstream errors map to
 * {@link LlmProviderException} carrying a numeric status at most; bodies are
 * consumed and discarded, never logged or exposed. Re-hydration stays
 * exclusively in {@code ResponseRehydrationService} - this provider never sees
 * mappings, never decrypts, never restores original values.</p>
 *
 * <p>No wiretap, no body logging, no retries, no per-request client creation.
 * The Phase 8 gateway timeout bounds every call; none is added here. One
 * processing attempt produces at most one outbound Anthropic request.</p>
 */
@Component
public class AnthropicLlmProvider implements LlmProvider {

    /** Generation bound required by the Messages API (not a model or business rule). */
    private static final int MAX_TOKENS = 4096;

    private final AnthropicProperties properties;
    private final WebClient webClient;

    public AnthropicLlmProvider(AnthropicProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public Mono<LlmResponse> complete(LlmRequest request) {
        if (request == null) {
            return Mono.error(new IllegalArgumentException("LLM request must not be null."));
        }
        if (!properties.isConfigured()) {
            // Fail before any network attempt: no key, no call, no fallback.
            return Mono.error(new LlmProviderException("Anthropic API key is not configured."));
        }
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "max_tokens", MAX_TOKENS,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", request.sanitizedText().value())));
        return webClient.post()
                .uri("/v1/messages")
                .header("x-api-key", properties.apiKey())
                .header("anthropic-version", properties.apiVersion())
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(ignored -> Mono.error(new LlmProviderException(
                                "Anthropic request failed with status "
                                        + response.statusCode().value() + "."))))
                .bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(
                        new LlmProviderException("Anthropic response did not contain usable text.")))
                .onErrorMap(e -> e instanceof DecodingException,
                        e -> new LlmProviderException("Anthropic response did not contain usable text."))
                .flatMap(json -> {
                    String text = extractText(json);
                    if (text == null) {
                        return Mono.error(new LlmProviderException(
                                "Anthropic response did not contain usable text."));
                    }
                    return Mono.just(new LlmResponse(request.requestId(), name(), text));
                });
    }

    /**
     * Extracts generated text from a Messages API payload: every
     * {@code content[]} block whose {@code type} is {@code text}, concatenated
     * in order. Returns {@code null} when no usable text exists.
     */
    private String extractText(JsonNode root) {
        if (root != null && root.isObject()) {
            JsonNode content = root.path("content");
            if (content.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode part : content) {
                    if ("text".equals(part.path("type").asText())) {
                        String chunk = part.path("text").asText(null);
                        if (chunk != null) {
                            text.append(chunk);
                        }
                    }
                }
                if (!text.toString().isBlank()) {
                    return text.toString();
                }
            }
        }
        return null;
    }
}
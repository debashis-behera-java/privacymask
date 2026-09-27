package com.privacymask.llm;

import com.privacymask.exception.LlmProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Real OpenAI provider behind the privacy boundary, using the OpenAI Responses
 * API ({@code POST {base-url}/v1/responses}, {@code {"model","input"}}) over a
 * non-blocking {@link WebClient} built once and shared.
 *
 * <p>Boundary enforcement (structural, not conventional):</p>
 * <ul>
 *   <li>Outbound {@code input} is the {@link SanitizedText} value only - the
 *       request type cannot carry anything else, so raw PII, mappings, keys,
 *       and detector internals cannot reach OpenAI.</li>
 *   <li>The API key travels solely on the {@code Authorization} header, read
 *       per request from configuration; a missing key fails here with no HTTP
 *       attempt, no fallback provider, and no retry.</li>
 *   <li>Responses are parsed into the provider-neutral {@link LlmResponse};
 *       only generated text is kept (output items, else the top-level
 *       convenience field). Empty/malformed payloads fail closed - the masked
 *       request is never returned as the answer.</li>
 *   <li>Upstream errors map to {@link LlmProviderException} carrying a numeric
 *       status at most; bodies are consumed and discarded, never logged or
 *       exposed. Re-hydration stays exclusively in
 *       {@code ResponseRehydrationService} - this provider never sees mappings.</li>
 * </ul>
 *
 * <p>No wiretap, no body logging, no retries, no per-request client creation.
 * The Phase 8 gateway timeout bounds every call; none is added here.</p>
 */
@Component
public class OpenAiLlmProvider implements LlmProvider {

    private final OpenAiProperties properties;
    private final WebClient webClient;

    public OpenAiLlmProvider(OpenAiProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public Mono<LlmResponse> complete(LlmRequest request) {
        if (request == null) {
            return Mono.error(new IllegalArgumentException("LLM request must not be null."));
        }
        if (!properties.isConfigured()) {
            // Fail before any network attempt: no key, no call, no fallback.
            return Mono.error(new LlmProviderException("OpenAI API key is not configured."));
        }
        Map<String, String> body = Map.of(
                "model", properties.model(),
                "input", request.sanitizedText().value());
        return webClient.post()
                .uri("/v1/responses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(ignored -> Mono.error(new LlmProviderException(
                                "OpenAI request failed with status "
                                        + response.statusCode().value() + "."))))
                .bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(
                        new LlmProviderException("OpenAI response did not contain usable text.")))
                .onErrorMap(e -> e instanceof DecodingException,
                        e -> new LlmProviderException("OpenAI response did not contain usable text."))
                .flatMap(json -> {
                    String text = extractText(json);
                    if (text == null) {
                        return Mono.error(new LlmProviderException(
                                "OpenAI response did not contain usable text."));
                    }
                    return Mono.just(new LlmResponse(request.requestId(), name(), text));
                });
    }

    /**
     * Extracts generated text from a Responses API payload: message items'
     * {@code output_text} parts first, then the top-level convenience field.
     * Returns {@code null} when no usable text exists.
     */
    private String extractText(JsonNode root) {
        if (root != null && root.isObject()) {
            JsonNode output = root.path("output");
            if (output.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode item : output) {
                    if (!"message".equals(item.path("type").asText())) {
                        continue;
                    }
                    JsonNode content = item.path("content");
                    if (!content.isArray()) {
                        continue;
                    }
                    for (JsonNode part : content) {
                        if ("output_text".equals(part.path("type").asText())) {
                            String chunk = part.path("text").asText(null);
                            if (chunk != null) {
                                text.append(chunk);
                            }
                        }
                    }
                }
                if (!text.toString().isBlank()) {
                    return text.toString();
                }
            }
            String convenience = root.path("output_text").asText(null);
            if (convenience != null && !convenience.isBlank()) {
                return convenience;
            }
        }
        return null;
    }
}

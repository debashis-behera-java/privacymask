package com.privacymask.service;

import com.privacymask.config.PrivacyMaskProperties;
import com.privacymask.dto.StatusResponse;
import com.privacymask.llm.AnthropicProperties;
import com.privacymask.llm.OpenAiProperties;
import com.privacymask.ratelimit.RateLimitProperties;
import com.privacymask.security.ApiKeyProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Builds the gateway status payload.
 */
@Service
public class StatusService {

    private final PrivacyMaskProperties properties;
    private final OpenAiProperties openAiProperties;
    private final AnthropicProperties anthropicProperties;
    private final ApiKeyProperties apiKeyProperties;
    private final RateLimitProperties rateLimitProperties;

    public StatusService(PrivacyMaskProperties properties,
                         OpenAiProperties openAiProperties,
                         AnthropicProperties anthropicProperties,
                         ApiKeyProperties apiKeyProperties,
                         RateLimitProperties rateLimitProperties) {
        this.properties = properties;
        this.openAiProperties = openAiProperties;
        this.anthropicProperties = anthropicProperties;
        this.apiKeyProperties = apiKeyProperties;
        this.rateLimitProperties = rateLimitProperties;
    }

    public Mono<StatusResponse> currentStatus() {
        StatusResponse response = new StatusResponse(
                properties.app().name(),
                "UP",
                properties.app().version(),
                openAiProperties.isConfigured(),
                anthropicProperties.isConfigured(),
                apiKeyProperties.isConfigured(),
                rateLimitProperties.isConfigured());
        return Mono.just(response);
    }
}

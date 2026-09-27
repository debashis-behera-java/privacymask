package com.privacymask.anthropic;

import com.privacymask.exception.LlmProviderException;
import com.privacymask.llm.AnthropicLlmProvider;
import com.privacymask.llm.AnthropicProperties;
import com.privacymask.llm.LlmProviderRegistry;
import com.privacymask.llm.LlmRequest;
import com.privacymask.llm.LlmResponse;
import com.privacymask.llm.MockLlmProvider;
import com.privacymask.llm.OpenAiLlmProvider;
import com.privacymask.llm.OpenAiProperties;
import com.privacymask.llm.SanitizedText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Anthropic provider against a deterministic local HTTP
 * stand-in: registration, request exactness (endpoint, headers, model, masked
 * body), the sanitized-only boundary, response parsing, error mapping, and
 * secret hygiene. No network, no key.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnthropicLlmProviderTests {

    private static final String TEST_KEY = "test-anthropic-key";
    private static final String MODEL = "test-claude-model";
    private static final String API_VERSION = "2024-test-version";

    private RecordingAnthropicServer server;
    private AnthropicLlmProvider provider;

    @BeforeEach
    void setUp() {
        server = new RecordingAnthropicServer();
        provider = new AnthropicLlmProvider(
                new AnthropicProperties(server.baseUrl(), TEST_KEY, MODEL, API_VERSION),
                WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void anthropicIsRegisteredCaseInsensitivelyAlongsideMockAndOpenai() {
        OpenAiLlmProvider openAi = new OpenAiLlmProvider(
                new OpenAiProperties(server.baseUrl(), "test-openai-key", "m"), WebClient.builder());
        LlmProviderRegistry registry =
                new LlmProviderRegistry(List.of(new MockLlmProvider(), openAi, provider));

        assertThat(registry.resolve("anthropic")).isSameAs(provider);
        assertThat(registry.resolve("ANTHROPIC")).isSameAs(provider);
        assertThat(registry.resolve("Anthropic")).isSameAs(provider);
        assertThat(registry.resolve("openai")).isSameAs(openAi);
        assertThat(registry.resolve("OPENAI")).isSameAs(openAi);
        assertThat(registry.resolve("mock")).isInstanceOf(MockLlmProvider.class);
        assertThat(registry.resolve("MOCK")).isInstanceOf(MockLlmProvider.class);
    }

    @Test
    void missingKeyFailsBeforeAnyHttpAttempt() {
        AnthropicLlmProvider keyless = new AnthropicLlmProvider(
                new AnthropicProperties(server.baseUrl(), "", MODEL, API_VERSION),
                WebClient.builder());

        StepVerifier.create(keyless.complete(request("Hello {{EMAIL_001}}.")))
                .verifyError(LlmProviderException.class);

        assertThat(server.requestCount()).isZero();
    }

    @Test
    void nullRequestFailsSafelyWithoutHttp() {
        StepVerifier.create(provider.complete(null))
                .verifyError(IllegalArgumentException.class);
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void outboundRequestHitsMessagesEndpointWithHeadersAndModel() {
        UUID requestId = UUID.randomUUID();
        server.setResponse(200, RecordingAnthropicServer.envelope("ok"));

        StepVerifier.create(provider.complete(new LlmRequest(
                        requestId, "anthropic",
                        SanitizedText.masked("Help {{EMAIL_001}}.", requestId))))
                .assertNext(response -> {
                    assertThat(response.requestId()).isEqualTo(requestId);
                    assertThat(response.provider()).isEqualTo("anthropic");
                    assertThat(response.responseText()).isEqualTo("ok");
                })
                .verifyComplete();

        RecordingAnthropicServer.Exchange exchange = server.exchanges().get(0);
        assertThat(exchange.method()).isEqualTo("POST");
        assertThat(exchange.path()).isEqualTo("/v1/messages");
        assertThat(exchange.apiKey()).isEqualTo(TEST_KEY);
        assertThat(exchange.apiVersion()).isEqualTo(API_VERSION);
        assertThat(exchange.body().path("model").asText()).isEqualTo(MODEL);
        assertThat(exchange.body().path("max_tokens").asInt()).isPositive();
        assertThat(exchange.body().path("messages").isArray()).isTrue();
        assertThat(exchange.body().path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(exchange.body().path("messages").get(0).path("content").asText())
                .isEqualTo("Help {{EMAIL_001}}.");
        assertThat(server.requestCount()).isEqualTo(1);
        // Credentials never travel in query parameters or body.
        assertThat(exchange.body().toString()).doesNotContain(TEST_KEY);
    }
@Test
    void multiPiiLeavesOnlyTokensInOutboundBody() {
        String masked = "Email {{EMAIL_001}} phone {{PHONE_002}} card {{CREDIT_CARD_003}} ssn {{SSN_004}}.";
        server.setResponse(200, RecordingAnthropicServer.envelope("ok"));

        StepVerifier.create(provider.complete(request(masked)))
                .expectNextCount(1)
                .verifyComplete();

        String body = server.exchanges().get(0).body().toString();
        assertThat(body).contains(
                "{{EMAIL_001}}", "{{PHONE_002}}", "{{CREDIT_CARD_003}}", "{{SSN_004}}");
        assertThat(body).doesNotContain("john@example.com", "9876543210", "4111 1111 1111 1111",
                "123-45-6789");
        // No mapping structures, keys, or internal types ride along.
        assertThat(body).doesNotContain(
                "TokenMappingStore", "mapping", "originalValue", "encrypted", "aes", "AES", TEST_KEY);
    }

    @Test
    void consecutiveTextBlocksAreConcatenated() {
        server.setResponse(200, "{\"content\":[{\"type\":\"text\",\"text\":\"one \"},"
                + "{\"type\":\"text\",\"text\":\"two\"}]}");

        StepVerifier.create(provider.complete(request("Hi.")))
                .assertNext(response -> assertThat(response.responseText()).isEqualTo("one two"))
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500})
    void upstreamErrorsFailClosedWithoutRetry(int status) {
        server.setResponse(status, "{\"error\":{\"type\":\"error\",\"message\":\"upstream says no\"}}");

        StepVerifier.create(provider.complete(request("Hi {{EMAIL_001}}.")))
                .verifyError(LlmProviderException.class);

        // Exactly one attempt: no retries, no fallback.
        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    void malformedResponsesFailClosed() {
        server.setResponse(200, "{}");
        StepVerifier.create(provider.complete(request("Hi.")))
                .verifyError(LlmProviderException.class);

        server.setResponse(200, "{\"content\":[]}");
        StepVerifier.create(provider.complete(request("Hi.")))
                .verifyError(LlmProviderException.class);

        server.setResponse(200, "{\"content\":[{\"type\":\"tool_use\"}]}");
        StepVerifier.create(provider.complete(request("Hi.")))
                .verifyError(LlmProviderException.class);

        server.setResponse(200, "{\"content\":[{\"type\":\"text\",\"text\":\"   \"}]}");
        StepVerifier.create(provider.complete(request("Hi.")))
                .verifyError(LlmProviderException.class);

        server.setResponse(200, "not json at all{{{");
        StepVerifier.create(provider.complete(request("Hi.")))
                .verifyError(LlmProviderException.class);
    }

    @Test
    void emptyResponseBodyFailsClosed() {
        server.setResponse(200, "");

        StepVerifier.create(provider.complete(request("Hi {{EMAIL_001}}.")))
                .verifyError(LlmProviderException.class);
    }

    @Test
    void failureCarriesNoSecretsOrInternals() {
        server.setResponse(401, "{\"error\":{\"type\":\"error\",\"message\":\"bad key\"}}");

        StepVerifier.create(provider.complete(request("Hi {{EMAIL_001}}.")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(LlmProviderException.class);
                    assertThat(error.getMessage()).doesNotContain(TEST_KEY);
                    assertThat(error.getMessage()).doesNotContain("bad key");
                })
                .verify();
    }

    @Test
    void keyAndPiiNeverReachLogs(CapturedOutput output) {
        server.setResponse(200, RecordingAnthropicServer.envelope("ok"));
        provider.complete(request("Contact {{EMAIL_001}} about john@example.com.")).block();

        server.setResponse(401, "{\"error\":{}}");
        try {
            provider.complete(request("Hi.")).block();
        } catch (LlmProviderException expected) {
            // Failure path also must stay silent.
        }

        assertThat(output.getOut()).doesNotContain(TEST_KEY, "john@example.com");
        assertThat(output.getErr()).doesNotContain(TEST_KEY, "john@example.com");
    }

    private LlmRequest request(String sanitizedText) {
        UUID requestId = UUID.randomUUID();
        return new LlmRequest(requestId, "anthropic", SanitizedText.masked(sanitizedText, requestId));
    }
}
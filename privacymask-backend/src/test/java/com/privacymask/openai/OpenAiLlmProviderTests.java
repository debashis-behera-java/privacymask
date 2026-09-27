package com.privacymask.openai;

import com.privacymask.exception.LlmProviderException;
import com.privacymask.llm.LlmProvider;
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
 * Unit tests for the OpenAI provider against a deterministic local HTTP
 * stand-in: registration, request exactness, the sanitized-only boundary,
 * response parsing, error mapping, and secret hygiene. No network, no key.
 */
@ExtendWith(OutputCaptureExtension.class)
class OpenAiLlmProviderTests {

    private static final String TEST_KEY = "test-openai-key";
    private static final String MODEL = "test-model";

    private RecordingOpenAiServer server;
    private OpenAiLlmProvider provider;

    @BeforeEach
    void setUp() {
        server = new RecordingOpenAiServer();
        provider = new OpenAiLlmProvider(
                new OpenAiProperties(server.baseUrl(), TEST_KEY, MODEL), WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void openaiIsRegisteredCaseInsensitivelyAlongsideMock() {
        LlmProviderRegistry registry =
                new LlmProviderRegistry(List.of(new MockLlmProvider(), provider));

        assertThat(registry.resolve("openai")).isSameAs(provider);
        assertThat(registry.resolve("OPENAI")).isSameAs(provider);
        assertThat(registry.resolve("OpenAI")).isSameAs(provider);
        assertThat(registry.resolve("mock")).isInstanceOf(MockLlmProvider.class);
        assertThat(registry.resolve("MOCK")).isInstanceOf(MockLlmProvider.class);
    }

    @Test
    void missingKeyFailsBeforeAnyHttpAttempt() {
        OpenAiLlmProvider keyless = new OpenAiLlmProvider(
                new OpenAiProperties(server.baseUrl(), "", MODEL), WebClient.builder());

        StepVerifier.create(keyless.complete(request("Hello {{EMAIL_001}}.")))
                .verifyError(LlmProviderException.class);

        assertThat(server.requestCount()).isZero();
    }

    @Test
    void outboundRequestIsExactAndSanitized() {
        UUID requestId = UUID.randomUUID();
        server.setResponse(200, RecordingOpenAiServer.envelope("ok"));

        StepVerifier.create(provider.complete(
                        new LlmRequest(requestId, "openai",
                                SanitizedText.masked("Help {{EMAIL_001}}.", requestId))))
                .assertNext(response -> {
                    assertThat(response.requestId()).isEqualTo(requestId);
                    assertThat(response.provider()).isEqualTo("openai");
                    assertThat(response.responseText()).isEqualTo("ok");
                })
                .verifyComplete();

        assertThat(server.requestCount()).isEqualTo(1);
        RecordingOpenAiServer.Exchange exchange = server.exchanges().get(0);
        assertThat(exchange.method()).isEqualTo("POST");
        assertThat(exchange.path()).isEqualTo("/v1/responses");
        // Internal inspection only (never logged): auth carries the key as Bearer.
        assertThat(exchange.authorization()).isEqualTo("Bearer " + TEST_KEY);
        assertThat(exchange.body().path("model").asText()).isEqualTo(MODEL);
        assertThat(exchange.body().path("input").asText()).isEqualTo("Help {{EMAIL_001}}.");
        // Minimum necessary data: model + sanitized input, nothing else.
        assertThat(exchange.body().size()).isEqualTo(2);
    }

    @Test
    void rawPiiNeverAppearsOutbound() {
        // The provider only forwards what it is given; this pins the body to the
        // sanitized input even for adversarial-looking content.
        String masked = "Email {{EMAIL_001}}, call {{PHONE_002}}, card {{CREDIT_CARD_003}}.";
        server.setResponse(200, RecordingOpenAiServer.envelope("noted."));

        StepVerifier.create(provider.complete(request(masked)))
                .expectNextCount(1)
                .verifyComplete();

        RecordingOpenAiServer.Exchange exchange = server.exchanges().get(0);
        String body = exchange.body().toString();
        assertThat(body).contains("{{EMAIL_001}}", "{{PHONE_002}}", "{{CREDIT_CARD_003}}");
        assertThat(body).doesNotContain(
                "john@example.com", "9876543210", "4111 1111 1111 1111");
    }

    @Test
    void convenienceOutputTextShapeParses() {
        server.setResponse(200, "{\"output_text\":\"plain reply\"}");

        StepVerifier.create(provider.complete(request("Hi.")))
                .assertNext(response -> assertThat(response.responseText()).isEqualTo("plain reply"))
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500})
    void upstreamErrorsFailClosedWithoutRetry(int status) {
        server.setResponse(status, "{\"error\":{\"message\":\"upstream says no\"}}");

        StepVerifier.create(provider.complete(request("Hi {{EMAIL_001}}.")))
                .verifyError(LlmProviderException.class);

        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    void malformedResponsesFailClosed() {
        server.setResponse(200, "{}");
        StepVerifier.create(provider.complete(request("Hi.")))
                .verifyError(LlmProviderException.class);

        server.setResponse(200, "{\"output\":[{\"type\":\"reasoning\"}]}");
        StepVerifier.create(provider.complete(request("Hi.")))
                .verifyError(LlmProviderException.class);

        server.setResponse(200, "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"   \"}]}]}");
        StepVerifier.create(provider.complete(request("Hi.")))
                .verifyError(LlmProviderException.class);

        server.setResponse(200, "not json at all{{{");
        StepVerifier.create(provider.complete(request("Hi.")))
                .verifyError(LlmProviderException.class);
    }

    @Test
    void failureCarriesNoSecretsOrInternals() {
        server.setResponse(401, "{\"error\":{\"message\":\"bad key\"}}");

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
        server.setResponse(200, RecordingOpenAiServer.envelope("ok"));
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
        return new LlmRequest(requestId, "openai", SanitizedText.masked(sanitizedText, requestId));
    }
}

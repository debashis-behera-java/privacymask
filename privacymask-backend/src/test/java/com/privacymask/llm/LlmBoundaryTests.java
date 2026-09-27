package com.privacymask.llm;

import com.privacymask.dto.AnalyzeRequest;
import com.privacymask.dto.AnalyzeResponse;
import com.privacymask.encryption.AesGcmEncryptionService;
import com.privacymask.mapping.TokenMappingStore;
import com.privacymask.service.PrivacyGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The central security contract: only sanitized text crosses the provider
 * boundary. Uses the full pipeline (detection, masking, encryption, mock
 * provider) and inspects the internally captured provider input.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class LlmBoundaryTests {

    @Autowired
    private PrivacyGatewayService gatewayService;

    @Autowired
    private MockLlmProvider mockProvider;

    @Autowired
    private TokenMappingStore store;

    @Autowired
    private AesGcmEncryptionService encryptionService;

    @BeforeEach
    void resetBoundaryCapture() {
        mockProvider.reset();
    }

    @Test
    void rawPiiNeverReachesProvider() {
        StepVerifier.create(gatewayService.analyze(
                        new AnalyzeRequest("Customer john@example.com called from +91 9876543210 regarding card 4111 1111 1111 1111.", "mock")))
                .assertNext(response -> assertThat(response.processedText()).isNotBlank())
                .verifyComplete();

        String providerInput = receivedText();

        assertThat(providerInput).contains("{{EMAIL_001}}", "{{PHONE_002}}", "{{CREDIT_CARD_003}}");
        assertThat(providerInput)
                .doesNotContain("john@example.com")
                .doesNotContain("9876543210")
                .doesNotContain("4111 1111 1111 1111");
    }

    @Test
    void emailIsMaskedBeforeProvider() {
        analyze("Customer email is john@example.com.");

        assertThat(receivedText()).isEqualTo("Customer email is {{EMAIL_001}}.");
    }

    @Test
    void phoneIsMaskedBeforeProvider() {
        analyze("Call +91 9876543210.");

        String providerInput = receivedText();
        assertThat(providerInput).contains("{{PHONE_001}}");
        assertThat(providerInput).doesNotContain("+91 9876543210");
    }

    @Test
    void creditCardIsMaskedBeforeProvider() {
        analyze("Card 4111 1111 1111 1111.");

        String providerInput = receivedText();
        assertThat(providerInput).contains("{{CREDIT_CARD_001}}");
        assertThat(providerInput).doesNotContain("4111 1111 1111 1111");
    }

    @Test
    void ssnIsMaskedBeforeProvider() {
        analyze("SSN 123-45-6789.");

        String providerInput = receivedText();
        assertThat(providerInput).contains("{{SSN_001}}");
        assertThat(providerInput).doesNotContain("123-45-6789");
    }

    @Test
    void duplicatePiiRemainsTokenizedBeforeProvider() {
        analyze("Email john@example.com. Confirm john@example.com.");

        String providerInput = receivedText();
        assertThat(providerInput).isEqualTo("Email {{EMAIL_001}}. Confirm {{EMAIL_001}}.");
        assertThat(providerInput).doesNotContain("john@example.com");
    }

    @Test
    void cleanTextReachesProviderUnchanged() {
        analyze("Please explain the refund policy.");

        assertThat(receivedText()).isEqualTo("Please explain the refund policy.");
    }

    @Test
    void providerIsCalledExactlyOnce() {
        analyze("Contact john@example.com.");

        assertThat(mockProvider.callCount()).isEqualTo(1);
    }

    @Test
    void providerInputCarriesRequestCorrelation() {
        AnalyzeResponse response = analyze("Contact john@example.com.");

        assertThat(mockProvider.lastReceived().requestId()).isEqualTo(response.requestId());
        assertThat(mockProvider.lastReceived().provider()).isEqualTo("mock");
    }

    @Test
    void requestContentCannotLeakAcrossRequests() {
        analyze("Mail alpha-unique-1@example.com now.");
        analyze("Mail beta-unique-2@example.com now.");

        String secondInput = receivedText();
        assertThat(secondInput).contains("{{EMAIL_001}}");
        assertThat(secondInput).doesNotContain("alpha-unique-1@example.com");
        assertThat(secondInput).doesNotContain("beta-unique-2@example.com");
    }

    @Test
    void providerTokensRestoreForClientWhileProviderSawTokensOnly() {
        AnalyzeResponse response = analyze("Customer email is john@example.com.");

        // Provider side: token in, raw value never.
        assertThat(receivedText()).contains("{{EMAIL_001}}");
        assertThat(receivedText()).doesNotContain("john@example.com");
        // Client side: masked pipeline text plus re-hydrated final response.
        assertThat(response.processedText()).isEqualTo("Customer email is {{EMAIL_001}}.");
        assertThat(response.response())
                .isEqualTo("Customer email is john@example.com. Mock analysis completed.");
        // Mapping side: sealed ciphertext that opens back to the original.
        String sealed = store.find(response.requestId(), "{{EMAIL_001}}")
                .map(mapping -> encryptionService.decrypt(mapping.encryptedValue()))
                .orElseThrow();
        assertThat(sealed).isEqualTo("john@example.com");
    }

    @Test
    void providerTokensNeverReachLogs(CapturedOutput output) {
        analyze("Contact john@example.com.");

        assertThat(output.getOut()).doesNotContain("{{EMAIL_001}}");
        assertThat(output.getErr()).doesNotContain("{{EMAIL_001}}");
    }

    private AnalyzeResponse analyze(String text) {
        return gatewayService.analyze(new AnalyzeRequest(text, "mock")).block();
    }

    private String receivedText() {
        LlmRequest received = mockProvider.lastReceived();
        assertThat(received).as("provider should have been called").isNotNull();
        return received.sanitizedText().value();
    }
}

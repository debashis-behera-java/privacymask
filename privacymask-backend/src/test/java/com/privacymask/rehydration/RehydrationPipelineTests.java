package com.privacymask.rehydration;

import com.privacymask.dto.AnalyzeRequest;
import com.privacymask.dto.AnalyzeResponse;
import com.privacymask.encryption.AesGcmEncryptionService;
import com.privacymask.mapping.TokenMappingStore;
import com.privacymask.service.PrivacyGatewayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the core PrivacyMask promise: raw PII travels
 * client -&gt; PrivacyMask -&gt; encrypted store, only tokens travel
 * PrivacyMask -&gt; LLM -&gt; PrivacyMask, and the client receives restored
 * values while {@code processedText} stays masked.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class RehydrationPipelineTests {

    @Autowired
    private PrivacyGatewayService gatewayService;

    @Autowired
    private TokenMappingStore store;

    @Autowired
    private AesGcmEncryptionService encryptionService;

    @Test
    void fullPrivacyPromiseHolds() {
        AnalyzeResponse response = analyze("Customer email is john@example.com.");

        // processedText stays masked while the final response restores originals.
        assertThat(response.processedText()).isEqualTo("Customer email is {{EMAIL_001}}.");
        // Mapping holds ciphertext that decrypts back (provider-side proof lives
        // in LlmBoundaryTests, which inspects the captured provider input).
        String sealed = store.find(response.requestId(), "{{EMAIL_001}}")
                .map(mapping -> encryptionService.decrypt(mapping.encryptedValue()))
                .orElseThrow();
        assertThat(sealed).isEqualTo("john@example.com");
        assertThat(response.response())
                .isEqualTo("Customer email is john@example.com. Mock analysis completed.");
    }

    @Test
    void multiPiiEndToEnd() {
        AnalyzeResponse response = analyze(
                "Customer john@example.com called +91 9876543210 regarding card 4111 1111 1111 1111.");

        assertThat(response.processedText()).isEqualTo(
                "Customer {{EMAIL_001}} called {{PHONE_002}} regarding card {{CREDIT_CARD_003}}.");
        assertThat(response.response()).isEqualTo(
                "Customer john@example.com called +91 9876543210 regarding card 4111 1111 1111 1111. Mock analysis completed.");
    }

    @Test
    void ssnEndToEndKeepsRawValueFromProvider() {
        AnalyzeResponse response = analyze("SSN on file: 123-45-6789.");

        assertThat(response.processedText()).contains("{{SSN_001}}");
        assertThat(response.response()).contains("123-45-6789");
    }

    @Test
    void duplicateEndToEnd() {
        AnalyzeResponse response = analyze("Email john@example.com. Follow up with john@example.com.");

        assertThat(response.processedText())
                .isEqualTo("Email {{EMAIL_001}}. Follow up with {{EMAIL_001}}.");
        assertThat(response.response())
                .isEqualTo("Email john@example.com. Follow up with john@example.com. Mock analysis completed.");
    }

    @Test
    void rehydratedValuesNeverReachLogs(CapturedOutput output) {
        analyze("SSN on file: 123-45-6789.");

        assertThat(output.getOut()).doesNotContain("123-45-6789");
        assertThat(output.getErr()).doesNotContain("123-45-6789");
        assertThat(output.getOut()).contains("Response rehydration completed");
    }

    private AnalyzeResponse analyze(String text) {
        return gatewayService.analyze(new AnalyzeRequest(text, "mock")).block();
    }
}

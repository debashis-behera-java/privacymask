package com.privacymask.openai;

import com.privacymask.dto.AnalyzeRequest;
import com.privacymask.dto.AnalyzeResponse;
import com.privacymask.service.PrivacyGatewayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in live probe against the real OpenAI API. NEVER runs in normal CI:
 * surefire ignores the {@code *IT} suffix, and the test additionally requires
 * {@code PRIVACYMASK_REAL_OPENAI_TEST=true} plus a valid
 * {@code PRIVACYMASK_OPENAI_API_KEY}. Nothing secret is printed; the PII below
 * is a demo value returned only to the calling test.
 */
@SpringBootTest
class RealOpenAiIT {

    @Autowired
    private PrivacyGatewayService gatewayService;

    @Test
    @EnabledIfEnvironmentVariable(named = "PRIVACYMASK_REAL_OPENAI_TEST", matches = "true")
    void liveOpenAiRoundTripKeepsPiiOutOfProviderInput() {
        String apiKey = System.getenv("PRIVACYMASK_OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "real test requires an API key");

        AnalyzeResponse response = gatewayService
                .analyze(new AnalyzeRequest(
                        "My email is john@example.com. Please explain the refund process.", "openai"))
                .block();

        assertThat(response).isNotNull();
        assertThat(response.processedText()).contains("{{EMAIL_001}}");
        assertThat(response.processedText()).doesNotContain("john@example.com");
        assertThat(response.response()).isNotBlank();
    }
}

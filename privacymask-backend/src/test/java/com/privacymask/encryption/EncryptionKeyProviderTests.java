package com.privacymask.encryption;

import com.privacymask.config.PrivacyMaskProperties;
import com.privacymask.exception.EncryptionConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for strict AES-256 key configuration: missing, malformed, and
 * wrong-length keys fail clearly; only Base64 of exactly 32 bytes is accepted.
 * Failure messages never carry key material.
 */
class EncryptionKeyProviderTests {

    private static final String VALID_KEY = "a1yaytmjjPfsvlbMzg3aCw//dujMSzodVeXBJ5hrsyk=";
    private static final String SHORT_KEY = "a1yaytmjjPfsvlbMzg3aCw="; // 16 bytes

    @Test
    void validKeyResolves() {
        assertThat(providerFor(VALID_KEY).getKey().getAlgorithm()).isEqualTo("AES");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void missingKeyFailsClearly(String key) {
        assertThatThrownBy(() -> providerFor(key).getKey())
                .isInstanceOf(EncryptionConfigurationException.class)
                .hasMessageContaining("PRIVACYMASK_ENCRYPTION_KEY");
    }

    @Test
    void nullEncryptionSectionFailsClearly() {
        PrivacyMaskProperties properties = new PrivacyMaskProperties(null, null, null, null, null);

        assertThatThrownBy(() -> new EncryptionKeyProvider(properties).getKey())
                .isInstanceOf(EncryptionConfigurationException.class);
    }

    @Test
    void invalidBase64Fails() {
        assertThatThrownBy(() -> providerFor("!!!not-base64!!!").getKey())
                .isInstanceOf(EncryptionConfigurationException.class);
    }

    @Test
    void wrongLengthFailsWithoutEchoingKey() {
        assertThatThrownBy(() -> providerFor(SHORT_KEY).getKey())
                .isInstanceOf(EncryptionConfigurationException.class)
                .hasMessageNotContaining(SHORT_KEY);
    }

    private EncryptionKeyProvider providerFor(String key) {
        return new EncryptionKeyProvider(new PrivacyMaskProperties(
                null, new PrivacyMaskProperties.Encryption(key), null, null, null));
    }
}

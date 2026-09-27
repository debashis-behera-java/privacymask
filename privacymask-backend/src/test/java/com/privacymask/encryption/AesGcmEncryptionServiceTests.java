package com.privacymask.encryption;

import com.privacymask.config.PrivacyMaskProperties;
import com.privacymask.exception.DecryptionFailedException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for AES-256-GCM round-trip, nonce, tamper, and key behavior.
 *
 * <p>Keys here are throwaway test values. No test key is a secret.</p>
 */
class AesGcmEncryptionServiceTests {

    private static final String KEY_A = "a1yaytmjjPfsvlbMzg3aCw//dujMSzodVeXBJ5hrsyk=";
    private static final String KEY_B = "w4CzE0y9r1yRrqx7nrxc2w5GJxK8nMk0r9H3P7nRnME=";

    private final AesGcmEncryptionService service = serviceFor(KEY_A);

    @Test
    void roundTripPreservesPlaintext() {
        String plaintext = "john@example.com";

        EncryptedValue encrypted = service.encrypt(plaintext);

        assertThat(service.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void roundTripPreservesUnicode() {
        String plaintext = "ग्राहक@example.com";

        assertThat(service.decrypt(service.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void emptyPlaintextRoundTrips() {
        // Documented decision: empty input encrypts to tag-only ciphertext and
        // decrypts back to empty - never silently null.
        assertThat(service.decrypt(service.encrypt(""))).isEqualTo("");
    }

    @Test
    void freshNoncePerEncryption() {
        EncryptedValue first = service.encrypt("john@example.com");
        EncryptedValue second = service.encrypt("john@example.com");

        assertThat(first.ivBase64()).isNotEqualTo(second.ivBase64());
        assertThat(first.ciphertextBase64()).isNotEqualTo(second.ciphertextBase64());
    }

    @Test
    void tamperedCiphertextFailsDecryption() {
        EncryptedValue encrypted = service.encrypt("john@example.com");

        EncryptedValue tampered = new EncryptedValue(
                flipFirstByte(encrypted.ciphertextBase64()), encrypted.ivBase64());

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(DecryptionFailedException.class)
                .hasMessageNotContaining("john@example.com");
    }

    @Test
    void tamperedIvFailsDecryption() {
        EncryptedValue encrypted = service.encrypt("john@example.com");

        EncryptedValue tampered = new EncryptedValue(
                encrypted.ciphertextBase64(), flipFirstByte(encrypted.ivBase64()));

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void wrongKeyFailsDecryption() {
        EncryptedValue encrypted = service.encrypt("john@example.com");

        assertThatThrownBy(() -> serviceFor(KEY_B).decrypt(encrypted))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void malformedInputsFailWithoutPlaintext() {
        assertThatThrownBy(() -> service.decrypt(null))
                .isInstanceOf(DecryptionFailedException.class);
        assertThatThrownBy(() -> service.decrypt(new EncryptedValue("!!!not-base64!!!", "!!!")))
                .isInstanceOf(DecryptionFailedException.class);
        assertThatThrownBy(() -> service.decrypt(new EncryptedValue(
                        Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)),
                        Base64.getEncoder().encodeToString(new byte[8]))))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void encryptedValueCarriesNoPlaintext() {
        String plaintext = "john@example.com";
        EncryptedValue encrypted = service.encrypt(plaintext);

        assertThat(encrypted.ciphertextBase64()).doesNotContain(plaintext);
        assertThat(encrypted.ivBase64()).doesNotContain(plaintext);
        assertThat(encrypted.toString()).doesNotContain(plaintext);
    }

    @Test
    void nullPlaintextIsRejected() {
        assertThatThrownBy(() -> service.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AesGcmEncryptionService serviceFor(String base64Key) {
        PrivacyMaskProperties properties = new PrivacyMaskProperties(
                null, new PrivacyMaskProperties.Encryption(base64Key), null, null, null);
        return new AesGcmEncryptionService(new EncryptionKeyProvider(properties));
    }

    private String flipFirstByte(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        bytes[0] ^= 0x01;
        return Base64.getEncoder().encodeToString(bytes);
    }
}

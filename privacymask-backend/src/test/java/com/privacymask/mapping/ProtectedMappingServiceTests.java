package com.privacymask.mapping;

import com.privacymask.detection.PiiType;
import com.privacymask.encryption.AesGcmEncryptionService;
import com.privacymask.encryption.EncryptedValue;
import com.privacymask.encryption.EncryptionKeyProvider;
import com.privacymask.config.PrivacyMaskProperties;
import com.privacymask.exception.EncryptionFailedException;
import com.privacymask.exception.MappingProtectionException;
import com.privacymask.masking.TokenMapping;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the seal-and-store step: stored entries decrypt back to the
 * original (proving ciphertext, not plaintext, is kept), and any crypto
 * failure fails closed with nothing stored.
 */
class ProtectedMappingServiceTests {

    private static final String TEST_KEY = "a1yaytmjjPfsvlbMzg3aCw//dujMSzodVeXBJ5hrsyk=";

    private ProtectedMappingService realService(TokenMappingStore store) {
        PrivacyMaskProperties properties = new PrivacyMaskProperties(
                null, new PrivacyMaskProperties.Encryption(TEST_KEY), null, null, null);
        AesGcmEncryptionService encryption =
                new AesGcmEncryptionService(new EncryptionKeyProvider(properties));
        return new ProtectedMappingService(encryption, store);
    }

    @Test
    void sealedMappingsDecryptToOriginals() {
        TokenMappingStore store = new TokenMappingStore(Duration.ofMinutes(10));
        ProtectedMappingService service = realService(store);
        UUID requestId = UUID.randomUUID();
        List<TokenMapping> plaintext = List.of(
                new TokenMapping("{{EMAIL_001}}", PiiType.EMAIL, "john@example.com", 8, 24),
                new TokenMapping("{{PHONE_002}}", PiiType.PHONE, "+91 9876543210", 33, 47));

        StepVerifier.create(service.protect(requestId, plaintext)).verifyComplete();

        AesGcmEncryptionService reader = reader();
        assertThat(decrypt(store, reader, requestId, "{{EMAIL_001}}")).isEqualTo("john@example.com");
        assertThat(decrypt(store, reader, requestId, "{{PHONE_002}}")).isEqualTo("+91 9876543210");
    }

    @Test
    void emptyMappingsNeedNoKeyAndStoreNothing() {
        // A provider without a key must still allow the no-PII path.
        PrivacyMaskProperties properties = new PrivacyMaskProperties(
                null, new PrivacyMaskProperties.Encryption(""), null, null, null);
        TokenMappingStore store = new TokenMappingStore(Duration.ofMinutes(10));
        ProtectedMappingService service = new ProtectedMappingService(
                new AesGcmEncryptionService(new EncryptionKeyProvider(properties)), store);

        StepVerifier.create(service.protect(UUID.randomUUID(), List.of())).verifyComplete();

        assertThat(store.liveEntryCount()).isZero();
    }

    @Test
    void encryptionFailureFailsClosedWithNothingStored() {
        AesGcmEncryptionService broken = mock(AesGcmEncryptionService.class);
        when(broken.encrypt(anyString())).thenThrow(new EncryptionFailedException("boom"));
        TokenMappingStore store = new TokenMappingStore(Duration.ofMinutes(10));
        ProtectedMappingService service = new ProtectedMappingService(broken, store);
        UUID requestId = UUID.randomUUID();
        List<TokenMapping> plaintext = List.of(
                new TokenMapping("{{EMAIL_001}}", PiiType.EMAIL, "john@example.com", 8, 24));

        StepVerifier.create(service.protect(requestId, plaintext))
                .verifyError(MappingProtectionException.class);

        assertThat(store.find(requestId, "{{EMAIL_001}}")).isEmpty();
        assertThat(store.liveEntryCount()).isZero();
    }

    @Test
    void missingKeyFailsClosedWithNothingStored() {
        PrivacyMaskProperties properties = new PrivacyMaskProperties(
                null, new PrivacyMaskProperties.Encryption(""), null, null, null);
        TokenMappingStore store = new TokenMappingStore(Duration.ofMinutes(10));
        ProtectedMappingService service = new ProtectedMappingService(
                new AesGcmEncryptionService(new EncryptionKeyProvider(properties)), store);
        UUID requestId = UUID.randomUUID();

        StepVerifier.create(service.protect(requestId, List.of(
                        new TokenMapping("{{EMAIL_001}}", PiiType.EMAIL, "john@example.com", 8, 24))))
                .verifyError(MappingProtectionException.class);

        assertThat(store.liveEntryCount()).isZero();
    }

    private AesGcmEncryptionService reader() {
        PrivacyMaskProperties properties = new PrivacyMaskProperties(
                null, new PrivacyMaskProperties.Encryption(TEST_KEY), null, null, null);
        return new AesGcmEncryptionService(new EncryptionKeyProvider(properties));
    }

    private String decrypt(TokenMappingStore store, AesGcmEncryptionService reader,
                           UUID requestId, String token) {
        EncryptedValue sealed = store.find(requestId, token)
                .map(EncryptedTokenMapping::encryptedValue)
                .orElseThrow();
        return reader.decrypt(sealed);
    }
}

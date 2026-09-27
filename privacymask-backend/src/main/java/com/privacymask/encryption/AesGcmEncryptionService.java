package com.privacymask.encryption;

import com.privacymask.exception.DecryptionFailedException;
import com.privacymask.exception.EncryptionFailedException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for token-mapping values.
 *
 * <p>Guarantees: transformation {@code AES/GCM/NoPadding} (never ECB), 256-bit
 * key from {@link EncryptionKeyProvider}, fresh 12-byte {@link SecureRandom}
 * nonce per encryption, 128-bit authentication tag verified on decryption.
 * A new {@link Cipher} is created per operation because ciphers are not
 * thread-safe; the shared {@link SecureRandom} is.</p>
 *
 * <p>Empty plaintext round-trips (GCM authenticates zero bytes like any other
 * input) rather than being special-cased to {@code null}. Nothing is logged
 * here - neither plaintext, ciphertext, nor key material.</p>
 *
 * <p>Memory note: Java {@link String}s are immutable and cannot be reliably
 * wiped; plaintext lifetime is minimized by encrypting immediately in the
 * pipeline and never retaining it afterwards. No stronger claim is made.</p>
 */
@Component
public class AesGcmEncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final EncryptionKeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmEncryptionService(EncryptionKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /**
     * Encrypts the given plaintext with a fresh random nonce.
     *
     * @param plaintext the value to protect, must not be {@code null}
     * @throws EncryptionFailedException if encryption cannot be completed
     */
    public EncryptedValue encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Plaintext must not be null.");
        }
        try {
            Key key = keyProvider.getKey();
            byte[] iv = new byte[GCM_NONCE_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(iv));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new EncryptionFailedException("Failed to encrypt value.", e);
        }
    }

    /**
     * Decrypts to the original plaintext, verifying authenticity first.
     *
     * <p>Tampered ciphertext/IV, wrong keys, and malformed inputs all fail here
     * with no partial output.</p>
     *
     * @throws DecryptionFailedException if the value cannot be authenticated
     *         and decrypted
     */
    public String decrypt(EncryptedValue encryptedValue) {
        if (encryptedValue == null
                || encryptedValue.ciphertextBase64() == null
                || encryptedValue.ivBase64() == null) {
            throw new DecryptionFailedException("Invalid encrypted value.");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(encryptedValue.ivBase64());
            if (iv.length != GCM_NONCE_BYTES) {
                throw new DecryptionFailedException("Invalid encrypted value.");
            }
            byte[] ciphertext = Base64.getDecoder().decode(encryptedValue.ciphertextBase64());
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.getKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DecryptionFailedException("Failed to decrypt value.", e);
        }
    }
}

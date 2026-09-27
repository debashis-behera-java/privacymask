package com.privacymask.encryption;

/**
 * Authenticated-encrypted bytes plus the nonce required to open them.
 *
 * <p>Both fields are Base64 (binary ciphertext is never handled as text). The
 * JCA {@code AES/GCM/NoPadding} implementation appends the 128-bit
 * authentication tag to the ciphertext, so no separate tag field is stored -
 * tag verification happens automatically on decryption. Contains no plaintext
 * and no key material.</p>
 */
public record EncryptedValue(
        String ciphertextBase64,
        String ivBase64) {
}

package com.privacymask.mapping;

import com.privacymask.encryption.AesGcmEncryptionService;
import com.privacymask.exception.MappingProtectionException;
import com.privacymask.masking.TokenMapping;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Seals transient plaintext token mappings into the request-scoped encrypted
 * store: PII -&gt; token -&gt; encrypt -&gt; store.
 *
 * <p>Fail-closed: any encryption or storage failure surfaces as
 * {@link MappingProtectionException} (no PII, ciphertext, or key material in
 * the signal), and callers must not produce output for the request. Plaintext
 * is never stored, logged, or retained here beyond the single pass.</p>
 */
@Component
public class ProtectedMappingService {

    private final AesGcmEncryptionService encryptionService;
    private final TokenMappingStore store;

    public ProtectedMappingService(AesGcmEncryptionService encryptionService, TokenMappingStore store) {
        this.encryptionService = encryptionService;
        this.store = store;
    }

    /**
     * Encrypts every mapping and stores the sealed set under the request ID.
     *
     * @return empty on success; error signal on any failure (fail closed)
     */
    public Mono<Void> protect(UUID requestId, List<TokenMapping> plaintextMappings) {
        if (plaintextMappings == null || plaintextMappings.isEmpty()) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            try {
                List<EncryptedTokenMapping> sealed = new ArrayList<>(plaintextMappings.size());
                for (TokenMapping mapping : plaintextMappings) {
                    sealed.add(new EncryptedTokenMapping(
                            mapping.token(),
                            mapping.type(),
                            encryptionService.encrypt(mapping.originalValue()),
                            mapping.start(),
                            mapping.end()));
                }
                store.store(requestId, sealed);
                return Mono.empty();
            } catch (RuntimeException e) {
                throw new MappingProtectionException("Failed to protect token mappings.", e);
            }
        });
    }
}

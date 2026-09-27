package com.privacymask.rehydration;

import com.privacymask.detection.PiiType;
import com.privacymask.encryption.AesGcmEncryptionService;
import com.privacymask.mapping.TokenMappingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restores original values into token-bearing LLM responses.
 *
 * <p>Contract (all decisions deliberate and documented):</p>
 * <ul>
 *   <li>Only exact tokens of the form {@code {{TYPE_NNN}}} with a known
 *       {@link PiiType} are looked up, strictly within the given
 *       {@code requestId} - never across requests. This also defeats
 *       {@code {{EMAIL_001}}}-vs-{@code {{EMAIL_0010}}} prefix confusion: the
 *       greedy digit run consumes the full number.</li>
 *   <li>Unknown, expired, or foreign-request tokens are left unchanged (no
 *       guessing, no cross-request search, no failure). Expired mappings read
 *       as absent because the store enforces TTL.</li>
 *   <li>Each unique token decrypts once per response (repeats reuse it).</li>
 *   <li>Atomicity: every required decryption runs BEFORE any replacement. A
 *       single tampered/wrong-key mapping fails the whole re-hydration with no
 *       partial output - never a half-restored response, never a fallback.</li>
 *   <li>Replacement uses {@link Matcher#quoteReplacement} so PII containing
 *       {@code $}, {@code \}, or other regex syntax restores exactly; UTF-8 is
 *       preserved untouched. The response is treated as untrusted data: plain
 *       substitution only, nothing interpreted or executed.</li>
 *   <li>Nothing is logged but {@code requestId} and the resolved token count -
 *       never values, mappings, or response bodies. Nothing is written back to
 *       the store; no plaintext cache exists anywhere here.</li>
 * </ul>
 *
 * <p>Reuses the centralized {@link AesGcmEncryptionService} - no duplicated
 * cryptographic code. Non-blocking and CPU-local, consistent with the pipeline.</p>
 */
@Component
public class ResponseRehydrationService {

    private static final Logger log = LoggerFactory.getLogger(ResponseRehydrationService.class);

    private static final Pattern TOKEN =
            Pattern.compile("\\{\\{([A-Z][A-Z_]*)_(\\d{3,})\\}\\}");

    private final TokenMappingStore store;
    private final AesGcmEncryptionService encryptionService;

    public ResponseRehydrationService(TokenMappingStore store, AesGcmEncryptionService encryptionService) {
        this.store = store;
        this.encryptionService = encryptionService;
    }

    /**
     * Re-hydrates valid tokens in the provider response for this request.
     *
     * @return the final response; unknown/expired tokens left as-is; decryption
     *         failure surfaces as an error signal (fail safely, no partial text)
     */
    public Mono<String> rehydrate(UUID requestId, String llmResponse) {
        if (llmResponse == null) {
            return Mono.error(new IllegalArgumentException("LLM response must not be null."));
        }
        return Mono.defer(() -> {
            Set<String> candidates = collectKnownTokens(llmResponse);
            if (candidates.isEmpty()) {
                return Mono.just(llmResponse);
            }
            Map<String, String> plaintextByToken = decryptAll(requestId, candidates);
            String rehydrated = replaceAll(llmResponse, plaintextByToken);
            log.info("Response rehydration completed requestId={} tokenCount={}",
                    requestId, plaintextByToken.size());
            return Mono.just(rehydrated);
        });
    }

    private Set<String> collectKnownTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            if (isKnownType(matcher.group(1))) {
                tokens.add(matcher.group());
            }
        }
        return tokens;
    }

    private boolean isKnownType(String typeName) {
        try {
            PiiType.valueOf(typeName);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Map<String, String> decryptAll(UUID requestId, Set<String> tokens) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String token : tokens) {
            store.find(requestId, token).ifPresent(mapping ->
                    resolved.put(token, encryptionService.decrypt(mapping.encryptedValue())));
        }
        return resolved;
    }

    private String replaceAll(String text, Map<String, String> plaintextByToken) {
        if (plaintextByToken.isEmpty()) {
            return text;
        }
        Matcher matcher = TOKEN.matcher(text);
        StringBuffer result = new StringBuffer(text.length());
        while (matcher.find()) {
            String replacement = plaintextByToken.get(matcher.group());
            matcher.appendReplacement(result, replacement == null
                    ? Matcher.quoteReplacement(matcher.group())
                    : Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}

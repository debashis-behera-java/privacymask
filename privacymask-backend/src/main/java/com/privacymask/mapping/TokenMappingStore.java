package com.privacymask.mapping;

import com.privacymask.config.PrivacyMaskProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, request-scoped store for encrypted token mappings.
 *
 * <p>Isolation: mappings are keyed by {@code requestId}, so identical token
 * names from different requests never intersect. The store holds ciphertext
 * only - plaintext never enters it.</p>
 *
 * <p>Lifecycle: entries expire {@code privacymask.mapping.ttl} after being
 * stored (kept available for future re-hydration, never forever). Expiry is
 * enforced lazily on reads plus opportunistically on writes - no background
 * threads, no blocking schedulers, nothing that can stall reactive event loops.
 * {@link ConcurrentHashMap} makes concurrent WebFlux requests safe. This is
 * explicitly the Phase 5 interim strategy; persistent storage comes later.</p>
 */
@Component
public class TokenMappingStore {

    private static final Duration FALLBACK_TTL = Duration.ofMinutes(10);

    private final Duration ttl;
    private final ConcurrentHashMap<UUID, StoredMappings> entries = new ConcurrentHashMap<>();

    @Autowired
    public TokenMappingStore(PrivacyMaskProperties properties) {
        this(properties != null && properties.mapping() != null ? properties.mapping().ttl() : null);
    }

    /**
     * Creates a store with a custom TTL (used by tests and future custom
     * wiring); production uses the configured {@code privacymask.mapping.ttl}.
     */
    public TokenMappingStore(Duration ttl) {
        this.ttl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? FALLBACK_TTL : ttl;
    }

    /**
     * Stores one request's encrypted mappings, replacing any previous entry.
     */
    public void store(UUID requestId, List<EncryptedTokenMapping> mappings) {
        purgeExpired(Instant.now());
        entries.put(requestId, new StoredMappings(
                mappings == null ? List.of() : List.copyOf(mappings),
                Instant.now().plus(ttl)));
    }

    /**
     * Finds one token's protected mapping for a request.
     *
     * @return empty when unknown, expired (expired entries are dropped), or
     *         the request ID is unknown - the three cases are deliberately
     *         indistinguishable to callers
     */
    public Optional<EncryptedTokenMapping> find(UUID requestId, String token) {
        StoredMappings stored = entries.get(requestId);
        if (stored == null) {
            return Optional.empty();
        }
        if (stored.isExpired(Instant.now())) {
            entries.remove(requestId);
            return Optional.empty();
        }
        return stored.mappings().stream()
                .filter(mapping -> mapping.token().equals(token))
                .findFirst();
    }

    /**
     * Current live entry count, for tests and diagnostics. Never the mappings.
     */
    int liveEntryCount() {
        purgeExpired(Instant.now());
        return entries.size();
    }

    private void purgeExpired(Instant now) {
        entries.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private record StoredMappings(List<EncryptedTokenMapping> mappings, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}

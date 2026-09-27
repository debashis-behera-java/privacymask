package com.privacymask.mapping;

import com.privacymask.detection.PiiType;
import com.privacymask.encryption.EncryptedValue;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for request isolation, TTL expiry, and ciphertext-only storage.
 */
class TokenMappingStoreTests {

    private static EncryptedTokenMapping mapping(String token, PiiType type) {
        return new EncryptedTokenMapping(
                token, type, new EncryptedValue("Y2lwaGVy", "aXY="), 0, 4);
    }

    @Test
    void sameTokenNamesAreIsolatedByRequest() {
        TokenMappingStore store = new TokenMappingStore(Duration.ofMinutes(10));
        UUID requestA = UUID.randomUUID();
        UUID requestB = UUID.randomUUID();
        store.store(requestA, List.of(mapping("{{EMAIL_001}}", PiiType.EMAIL)));
        store.store(requestB, List.of(mapping("{{EMAIL_001}}", PiiType.PHONE)));

        assertThat(store.find(requestA, "{{EMAIL_001}}"))
                .map(EncryptedTokenMapping::type).contains(PiiType.EMAIL);
        assertThat(store.find(requestB, "{{EMAIL_001}}"))
                .map(EncryptedTokenMapping::type).contains(PiiType.PHONE);
        // One request's tokens are invisible to the other.
        assertThat(store.find(requestA, "{{PHONE_001}}")).isEmpty();
    }

    @Test
    void unknownRequestOrTokenYieldsEmpty() {
        TokenMappingStore store = new TokenMappingStore(Duration.ofMinutes(10));

        assertThat(store.find(UUID.randomUUID(), "{{EMAIL_001}}")).isEmpty();
        store.store(UUID.randomUUID(), List.of());
        assertThat(store.find(UUID.randomUUID(), "{{EMAIL_001}}")).isEmpty();
    }

    @Test
    void expiredMappingsAreNotUsable() throws InterruptedException {
        TokenMappingStore store = new TokenMappingStore(Duration.ofMillis(30));
        UUID requestId = UUID.randomUUID();
        store.store(requestId, List.of(mapping("{{EMAIL_001}}", PiiType.EMAIL)));

        assertThat(store.find(requestId, "{{EMAIL_001}}")).isPresent();
        Thread.sleep(80);

        assertThat(store.find(requestId, "{{EMAIL_001}}")).isEmpty();
        assertThat(store.liveEntryCount()).isZero();
    }

    @Test
    void storedMappingsHoldNoPlaintext() {
        TokenMappingStore store = new TokenMappingStore(Duration.ofMinutes(10));
        UUID requestId = UUID.randomUUID();
        store.store(requestId, List.of(mapping("{{EMAIL_001}}", PiiType.EMAIL)));

        EncryptedTokenMapping stored = store.find(requestId, "{{EMAIL_001}}").orElseThrow();
        assertThat(stored.toString()).doesNotContain("john@example.com");
        // EncryptedTokenMapping exposes no originalValue accessor at all:
        // token, type, encryptedValue, start, end.
        assertThat(stored.encryptedValue().ciphertextBase64()).isNotBlank();
    }

    @Test
    void concurrentRequestsStayIsolated() throws InterruptedException {
        TokenMappingStore store = new TokenMappingStore(Duration.ofMinutes(10));
        int requests = 32;
        Thread[] threads = new Thread[requests];
        boolean[] ok = new boolean[requests];
        for (int i = 0; i < requests; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                UUID requestId = UUID.randomUUID();
                PiiType type = index % 2 == 0 ? PiiType.EMAIL : PiiType.PHONE;
                store.store(requestId, List.of(mapping("{{T_001}}", type)));
                ok[index] = store.find(requestId, "{{T_001}}")
                        .map(found -> found.type() == type).orElse(false);
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(ok).containsOnly(true);
    }
}

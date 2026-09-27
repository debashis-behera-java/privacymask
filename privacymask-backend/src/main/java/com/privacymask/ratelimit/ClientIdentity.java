package com.privacymask.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Derives the rate limiter's client identity from the authenticated
 * credential.
 *
 * <p>The limiter must be keyed by the authenticated API client, but the raw
 * API key must never be stored, logged, or otherwise observable as an
 * identifier. This helper reduces the credential to a SHA-256 hex digest used
 * solely as an in-memory map key. The digest is a one-way, non-secret
 * representation: it cannot be reversed into the credential and it is never
 * written to logs, responses, mappings, or provider requests.</p>
 */
final class ClientIdentity {

    private ClientIdentity() {
    }

    /**
     * Returns the hex-encoded SHA-256 digest of the given credential.
     * Pure function of its input; performs no logging or I/O.
     */
    static String sha256Hex(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java runtime; failure here is a
            // programming error, never a request-handling condition.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

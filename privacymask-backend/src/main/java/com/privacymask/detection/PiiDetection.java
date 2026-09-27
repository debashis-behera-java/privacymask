package com.privacymask.detection;

/**
 * A single detected PII occurrence.
 *
 * <p>Immutable. {@code value} is the exact original substring (never normalized)
 * and {@code start}/{@code end} are offsets into the original input string, with
 * {@code end} exclusive: {@code input.substring(start, end).equals(value)}.</p>
 *
 * <p>No confidence score: regex matches are deterministic, and inventing scores
 * would be a fake guarantee.</p>
 */
public record PiiDetection(
        PiiType type,
        String value,
        int start,
        int end) {
}

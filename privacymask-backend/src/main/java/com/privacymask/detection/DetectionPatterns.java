package com.privacymask.detection;

import java.util.regex.Pattern;

/**
 * Central home for all Phase 3 detection patterns.
 *
 * <p>Patterns are compiled once as {@code static final} constants instead of per
 * request: detection runs inline on the gateway hot path, so avoiding repeated
 * compilation matters, while anything fancier would be premature optimization.</p>
 *
 * <p>These patterns are intentionally heuristic - they detect common shapes, not
 * every PII occurrence in existence. See each constant for its documented
 * trade-offs.</p>
 */
public final class DetectionPatterns {

    private DetectionPatterns() {
    }

    /**
     * Common email shape: local part, {@code @}, domain, and a 2+ letter TLD.
     * Covers {@code john@example.com}, dotted and {@code +}-tagged local parts.
     * Rejects obvious junk like {@code john@example} (no TLD). Word boundaries
     * keep trailing sentence punctuation (e.g. {@code .}) out of the match.
     */
    public static final Pattern EMAIL =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    /**
     * Structural phone candidate: optional {@code +<cc>}, optional parenthesized
     * area code (which must be followed by subscriber digits, so {@code (555)}
     * alone never matches), then digits mixed with common separators.
     * Deliberately broad - {@link RegexPiiDetector} post-validates digit counts
     * and shape, and {@link DetectionEngine} lets more specific types (card, SSN,
     * IP) win overlaps. Boundaries prevent matching substrings of longer runs:
     * the match may neither continue into separator+digit (so an invalid card
     * like {@code 4111 1111 1111 1112} yields no phone prefix) nor start right
     * after digit+separator.
     */
    public static final Pattern PHONE =
            Pattern.compile("(?<!\\d)(?<!\\d[\\s().-])(?:\\+\\d{1,3}[\\s.-]?)?(?:\\(\\d{2,5}\\)[\\s.-]?\\d[\\d\\s().-]{4,15}\\d|\\d[\\d\\s().-]{5,16}\\d)(?![\\s().-]*\\d)");

    /**
     * Card-like run: 13-19 digits possibly separated by spaces, hyphens, or dots.
     * Letter lookarounds keep it out of tokens/identifiers. Every candidate must
     * additionally pass {@link LuhnValidator} - without it any long number would
     * match, which is exactly the false positive the Luhn step exists to prevent.
     */
    public static final Pattern CREDIT_CARD_CANDIDATE =
            Pattern.compile("(?<![A-Za-z0-9])(?:\\d[\\s.-]?){13,19}(?![A-Za-z0-9])");

    /**
     * Strict dashed US SSN with alphanumeric boundaries, so {@code 123-45-6789}
     * matches but neither {@code 123456789} nor {@code X123-45-6789Y} does.
     * Pattern conformance only - existence is never verified.
     */
    public static final Pattern SSN =
            Pattern.compile("(?<![A-Za-z0-9])\\d{3}-\\d{2}-\\d{4}(?![A-Za-z0-9])");

    /**
     * Dotted-quad candidate. Regex alone cannot enforce 0-255, so
     * {@link RegexPiiDetector} validates each octet and drops junk like
     * {@code 999.999.999.999}. Dot lookarounds stop partial matches inside
     * longer dotted sequences (e.g. version numbers).
     */
    public static final Pattern IPV4_CANDIDATE =
            Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");

    /**
     * {@code http(s)://} URL up to whitespace or delimiters. Trailing sentence
     * punctuation is trimmed by {@link RegexPiiDetector} after matching so
     * {@code "Visit https://example.com."} yields {@code https://example.com}.
     */
    public static final Pattern URL =
            Pattern.compile("https?://[^\\s<>\"'()\\[\\]{}]+");
}

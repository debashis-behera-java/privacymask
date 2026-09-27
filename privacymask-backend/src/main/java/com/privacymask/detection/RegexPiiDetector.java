package com.privacymask.detection;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Regex-based {@link PiiDetector} for the six Phase 3 PII types.
 *
 * <p>Each detector method is intentionally small: match candidates with a
 * pre-compiled pattern, post-validate (Luhn, octet range, digit counts, trailing
 * punctuation), and report exact original-text offsets. Anything a pattern alone
 * cannot decide is validated in code rather than with a cleverer regex.</p>
 *
 * <p>Stateless and thread-safe - safe to share across reactive pipelines.</p>
 */
@Component
public class RegexPiiDetector implements PiiDetector {

    private static final int MIN_PHONE_DIGITS = 7;
    private static final int MAX_PHONE_DIGITS = 15;
    private static final int PLAIN_DIGIT_PHONE_LENGTH = 10;

    /** Trailing characters that end a sentence, not a URL. */
    private static final String URL_TRAILING_PUNCTUATION = ".,;:!?)]}\"'";

    @Override
    public List<PiiDetection> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiDetection> detections = new ArrayList<>();
        detectEmails(text, detections);
        detectUrls(text, detections);
        detectIpAddresses(text, detections);
        detectCreditCards(text, detections);
        detectSsns(text, detections);
        detectPhones(text, detections);
        return List.copyOf(detections);
    }

    private void detectEmails(String text, List<PiiDetection> detections) {
        Matcher matcher = DetectionPatterns.EMAIL.matcher(text);
        while (matcher.find()) {
            detections.add(new PiiDetection(PiiType.EMAIL, matcher.group(), matcher.start(), matcher.end()));
        }
    }

    private void detectUrls(String text, List<PiiDetection> detections) {
        Matcher matcher = DetectionPatterns.URL.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            // Trim sentence punctuation ("Visit https://example.com." -> without the dot).
            int end = raw.length();
            while (end > 0 && URL_TRAILING_PUNCTUATION.indexOf(raw.charAt(end - 1)) >= 0) {
                end--;
            }
            if (end == 0) {
                continue;
            }
            detections.add(new PiiDetection(PiiType.URL, raw.substring(0, end), matcher.start(), matcher.start() + end));
        }
    }

    private void detectIpAddresses(String text, List<PiiDetection> detections) {
        Matcher matcher = DetectionPatterns.IPV4_CANDIDATE.matcher(text);
        while (matcher.find()) {
            if (isValidIpv4(matcher.group())) {
                detections.add(new PiiDetection(PiiType.IP_ADDRESS, matcher.group(), matcher.start(), matcher.end()));
            }
        }
    }

    private boolean isValidIpv4(String candidate) {
        String[] octets = candidate.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            int value = 0;
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    private void detectCreditCards(String text, List<PiiDetection> detections) {
        Matcher matcher = DetectionPatterns.CREDIT_CARD_CANDIDATE.matcher(text);
        while (matcher.find()) {
            // The candidate pattern tolerates a trailing separator, which belongs
            // to the sentence, not the number ("...1111." must keep its period).
            String raw = matcher.group();
            int end = raw.length();
            while (end > 0 && !Character.isDigit(raw.charAt(end - 1))) {
                end--;
            }
            if (end == 0) {
                continue;
            }
            String value = raw.substring(0, end);
            // Luhn is the gate: without it any long number would match.
            if (LuhnValidator.isValidCandidate(value)) {
                detections.add(new PiiDetection(
                        PiiType.CREDIT_CARD, value, matcher.start(), matcher.start() + end));
            }
        }
    }

    private void detectSsns(String text, List<PiiDetection> detections) {
        Matcher matcher = DetectionPatterns.SSN.matcher(text);
        while (matcher.find()) {
            detections.add(new PiiDetection(PiiType.SSN, matcher.group(), matcher.start(), matcher.end()));
        }
    }

    private void detectPhones(String text, List<PiiDetection> detections) {
        Matcher matcher = DetectionPatterns.PHONE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (isPlausiblePhone(candidate)) {
                detections.add(new PiiDetection(PiiType.PHONE, candidate, matcher.start(), matcher.end()));
            }
        }
    }

    /**
     * Structural plausibility check for the deliberately broad phone pattern:
     * 7-15 digits total; separator-free runs must be exactly 10 digits (so order
     * IDs and card fragments are not flagged); dotted quads are (attempted) IPs,
     * never phones.
     */
    private boolean isPlausiblePhone(String candidate) {
        int digits = 0;
        boolean hasSeparator = false;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c != '+' && c != '(' && c != ')') {
                hasSeparator = true;
            }
        }
        if (digits < MIN_PHONE_DIGITS || digits > MAX_PHONE_DIGITS) {
            return false;
        }
        if (!hasSeparator && digits != PLAIN_DIGIT_PHONE_LENGTH) {
            return false;
        }
        return !candidate.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}

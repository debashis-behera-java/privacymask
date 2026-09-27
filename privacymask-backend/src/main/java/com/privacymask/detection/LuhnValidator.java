package com.privacymask.detection;

/**
 * Luhn checksum validation for card-number candidates.
 *
 * <p>Focused single-purpose utility: it answers "could this digit string be a
 * real card number?" so the detector does not flag every long number. Never
 * receives, logs, or stores anything but the candidate digits it validates.</p>
 */
public final class LuhnValidator {

    private LuhnValidator() {
    }

    /**
     * Validates a digit string with the Luhn algorithm.
     *
     * @param digits digits only (no spaces or hyphens), may be {@code null}
     * @return {@code true} if the checksum passes and the input is plausible:
     *         13-19 digits and not all identical (e.g. all zeros pass the raw
     *         checksum but are never real card numbers)
     */
    public static boolean isValid(CharSequence digits) {
        if (digits == null || digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        boolean allSame = true;
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            if (c != digits.charAt(digits.length() - 1)) {
                allSame = false;
            }
            int n = c - '0';
            if (doubleDigit) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            doubleDigit = !doubleDigit;
        }
        return !allSame && sum % 10 == 0;
    }

    /**
     * Strips spaces, hyphens, and dots from a raw candidate, then validates.
     *
     * @param candidate raw matched text such as {@code "4111 1111 1111 1111"}
     */
    public static boolean isValidCandidate(String candidate) {
        if (candidate == null) {
            return false;
        }
        StringBuilder digits = new StringBuilder(candidate.length());
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c != ' ' && c != '-' && c != '.') {
                return false;
            }
        }
        return isValid(digits);
    }
}

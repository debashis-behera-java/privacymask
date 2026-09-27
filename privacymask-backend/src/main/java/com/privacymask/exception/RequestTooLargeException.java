package com.privacymask.exception;

/**
 * Raised when request text exceeds the configured maximum length.
 *
 * <p>Carries only the numeric limit - never the offending text. Handled as
 * HTTP 413 before any detection, encryption, mapping, or provider work runs.</p>
 */
public class RequestTooLargeException extends RuntimeException {

    private final int maxTextLength;

    public RequestTooLargeException(int maxTextLength) {
        super("Request text exceeds the maximum allowed length of " + maxTextLength + " characters.");
        this.maxTextLength = maxTextLength;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }
}

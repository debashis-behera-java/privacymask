package com.privacymask.request;

import com.privacymask.config.PrivacyMaskProperties;
import com.privacymask.exception.RequestTooLargeException;
import org.springframework.stereotype.Component;

/**
 * Enforces the configured request-text size limit before any expensive or
 * sensitive work (detection, encryption, mapping, provider calls).
 *
 * <p>Length is measured in Java characters ({@link String#length()}, i.e.
 * UTF-16 code units): deterministic across ASCII, BMP Unicode, and
 * surrogate-pair emoji (one emoji counts as two). Null is tolerated here -
 * null/blank rejection belongs to bean validation, which runs earlier.</p>
 */
@Component
public class RequestSizeValidator {

    private final int maxTextLength;

    public RequestSizeValidator(PrivacyMaskProperties properties) {
        this.maxTextLength = properties.request().maxTextLength();
    }

    /**
     * Rejects over-limit input.
     *
     * @throws RequestTooLargeException when {@code text.length()} exceeds the limit
     */
    public void validate(String text) {
        if (text != null && text.length() > maxTextLength) {
            throw new RequestTooLargeException(maxTextLength);
        }
    }

    int maxTextLength() {
        return maxTextLength;
    }
}

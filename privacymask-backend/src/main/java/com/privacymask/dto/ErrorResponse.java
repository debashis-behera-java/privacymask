package com.privacymask.dto;

import java.time.Instant;

/**
 * Standard error payload returned by {@link com.privacymask.exception.GlobalExceptionHandler}.
 *
 * <p>Never contains stack traces, headers, secrets, or request bodies.</p>
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path) {
}

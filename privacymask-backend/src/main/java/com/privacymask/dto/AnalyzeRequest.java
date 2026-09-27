package com.privacymask.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Gateway analyze request. Internal domain objects are never exposed via the API.
 *
 * <p>The request ID is always generated server-side and is therefore not part of
 * this contract.</p>
 */
public record AnalyzeRequest(
        @NotBlank(message = "text is required") String text,
        @NotBlank(message = "provider is required") String provider) {
}

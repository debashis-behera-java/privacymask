package com.privacymask.security;

import org.springframework.security.core.AuthenticationException;

/**
 * Raised when API-key authentication fails (missing key, wrong key, or no key
 * configured). Never carries the provided or expected credential in its message.
 */
public class ApiKeyAuthenticationException extends AuthenticationException {

    public ApiKeyAuthenticationException(String message) {
        super(message);
    }
}

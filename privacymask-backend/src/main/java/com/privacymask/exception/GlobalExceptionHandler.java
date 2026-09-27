package com.privacymask.exception;

import com.privacymask.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Centralized reactive exception handling.
 *
 * <p>Security rules enforced here:</p>
 * <ul>
 *   <li>Never expose stack traces or internal details to API clients.</li>
 *   <li>Never log request bodies, headers, tokens, API keys, or PII -
 *       only method, path, and status are logged.</li>
 * </ul>
 */
@Order(-2)
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleResponseStatus(
            ResponseStatusException ex, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return build(status, sanitize(ex.getReason()), exchange, ex);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleBadInput(
            ServerWebInputException ex, ServerWebExchange exchange) {
        return build(HttpStatus.BAD_REQUEST, "Invalid request.", exchange, ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleIllegalArgument(
            IllegalArgumentException ex, ServerWebExchange exchange) {
        return build(HttpStatus.BAD_REQUEST, "Invalid request.", exchange, ex);
    }

    @ExceptionHandler(UnsupportedProviderException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleUnsupportedProvider(
            UnsupportedProviderException ex, ServerWebExchange exchange) {
        String provider = ex.getProvider();
        String message = (provider == null || provider.isBlank())
                ? "Unsupported provider."
                : "Unsupported provider: " + sanitize(provider);
        return build(HttpStatus.BAD_REQUEST, message, exchange, ex);
    }

    @ExceptionHandler(RequestTooLargeException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleRequestTooLarge(
            RequestTooLargeException ex, ServerWebExchange exchange) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, sanitize(ex.getMessage()), exchange, ex);
    }

    @ExceptionHandler(LlmProviderException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleProviderFailure(
            LlmProviderException ex, ServerWebExchange exchange) {
        return build(HttpStatus.BAD_GATEWAY, "LLM provider request failed.", exchange, ex);
    }

    @ExceptionHandler({ProviderTimeoutException.class, java.util.concurrent.TimeoutException.class})
    public Mono<ResponseEntity<ErrorResponse>> handleProviderTimeout(
            Exception ex, ServerWebExchange exchange) {
        return build(HttpStatus.GATEWAY_TIMEOUT, "LLM provider request timed out.", exchange, ex);
    }

    @ExceptionHandler(EncryptionConfigurationException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleEncryptionConfiguration(
            EncryptionConfigurationException ex, ServerWebExchange exchange) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Encryption is not configured correctly.", exchange, ex);
    }

    @ExceptionHandler({EncryptionFailedException.class, MappingProtectionException.class,
            DecryptionFailedException.class})
    public Mono<ResponseEntity<ErrorResponse>> handleProtectionFailure(
            RuntimeException ex, ServerWebExchange exchange) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process request securely.", exchange, ex);
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleUnexpected(
            Exception ex, ServerWebExchange exchange) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error.", exchange, ex);
    }

    private Mono<ResponseEntity<ErrorResponse>> build(
            HttpStatus status, String message, ServerWebExchange exchange, Throwable ex) {
        String path = exchange.getRequest().getPath().value();
        // Deliberately log only non-sensitive request metadata (method + path + status).
        // Never log headers, bodies, tokens, or PII.
        log.warn("Request failed: {} {} -> {}", exchange.getRequest().getMethod(), path, status.value());
        log.debug("Failure cause for {} {}: {}: {}", exchange.getRequest().getMethod(), path,
                ex.getClass().getSimpleName(), sanitize(ex.getMessage()));

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path);
        return Mono.just(ResponseEntity.status(status).body(body));
    }

    /**
     * Defensive: never propagate raw internal messages (which could leak paths,
     * SQL, or bean details) when no safe message is available.
     */
    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "Request failed.";
        }
        // Cap length to avoid reflecting oversized/malicious payloads in responses.
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}

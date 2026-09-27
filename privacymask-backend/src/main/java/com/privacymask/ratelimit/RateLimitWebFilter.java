package com.privacymask.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privacymask.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Enforces the Phase 12 rate limit on the protected processing endpoint.
 *
 * <p>Position in the pipeline (wired in {@code SecurityConfig} after
 * authorization):</p>
 * <pre>
 * HTTP request → API-key authentication → authorization → THIS FILTER
 * → PrivacyMask controller → PII detection → … → LLM provider
 * </pre>
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Only {@code POST /api/v1/privacymask/analyze} is checked. Status,
 *       unknown routes, and other methods pass through untouched, so the
 *       limiter can neither break the status endpoint nor turn unknown
 *       routes into 429s.</li>
 *   <li>When disabled via configuration, every request passes through;
 *       authentication (a separate, earlier layer) stays fully enforced.</li>
 *   <li>Unauthenticated requests are never denied here: they cannot have
 *       passed authorization, and defensively this filter chains them
 *       through instead of accounting them. Unauthorized callers therefore
 *       return 401 and never drain the authenticated client's quota.</li>
 *   <li>Client identity is the SHA-256 digest of the authenticated API key
 *       (see {@link ClientIdentity}); the raw credential is never stored,
 *       never logged, and never leaves this exchange.</li>
 *   <li>Denied requests receive {@code 429 Too Many Requests} with a JSON
 *       {@link ErrorResponse} body and a {@code Retry-After} header, and —
 *       crucially — never reach the controller, so no PII detection,
 *       tokenization, encryption, mapping, provider call, or re-hydration
 *       occurs.</li>
 * </ul>
 * <p>Not a Spring bean: it is constructed inside {@code SecurityConfig} and
 * attached to the security chain only. A {@code WebFilter} bean would also
 * be auto-registered in Boot's main filter chain and run twice per request
 * (double quota consumption, double response writes).</p>
 */
public class RateLimitWebFilter implements WebFilter {

    static final String ANALYZE_PATH = "/api/v1/privacymask/analyze";
    static final String RETRY_AFTER_HEADER = "Retry-After";

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebFilter.class);

    private final RateLimitProperties properties;
    private final TokenBucketRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitWebFilter(
            RateLimitProperties properties, TokenBucketRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isProtectedProcessingRequest(exchange)) {
            return chain.filter(exchange);
        }
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }
        // NOTE: every branch below must EMIT a value before switchIfEmpty.
        // flatMap over Mono<Void> inners completes empty, which would make
        // switchIfEmpty re-subscribe chain.filter and execute the request
        // twice (double quota consumption, double controller invocation).
        // thenReturn(...) guards against that Reactor footgun.
        return exchange.getPrincipal()
                .ofType(Authentication.class)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .ofType(String.class)
                .filter(apiKey -> !apiKey.isBlank())
                .map(ClientIdentity::sha256Hex)
                .map(rateLimiter::tryAcquire)
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        return chain.filter(exchange).thenReturn(Boolean.TRUE);
                    }
                    // Safe observability only: decision + retry estimate,
                    // never identity or credential material, never bodies/PII.
                    log.warn("Rate limit exceeded for authenticated client; retry after {}s.",
                            decision.retryAfterSeconds());
                    return writeTooManyRequests(exchange, decision.retryAfterSeconds())
                            .thenReturn(Boolean.FALSE);
                })
                // Unauthenticated (or blank-credential) requests have no
                // identity to account: pass through. Authorization ahead
                // already rejected truly unauthenticated callers with 401.
                // The FALSE value is discarded by then() below.
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(Boolean.FALSE)))
                .then();
    }

    private static boolean isProtectedProcessingRequest(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() == HttpMethod.POST
                && ANALYZE_PATH.equals(exchange.getRequest().getPath().value());
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange, long retryAfterSeconds) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(RETRY_AFTER_HEADER, Long.toString(retryAfterSeconds));
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "Too many requests.",
                exchange.getRequest().getPath().value());
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            return exchange.getResponse()
                    .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        } catch (Exception e) {
            byte[] fallback = "{\"status\":429,\"message\":\"Too many requests.\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return exchange.getResponse()
                    .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(fallback)));
        }
    }
}

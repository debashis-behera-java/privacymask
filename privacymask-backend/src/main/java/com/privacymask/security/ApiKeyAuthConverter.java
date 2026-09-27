package com.privacymask.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reads the {@code X-API-Key} header and converts it into an
 * {@link Authentication} token for the authentication manager.
 * Returns {@code Mono.empty()} when the header is absent or blank, so the
 * request proceeds unauthenticated (and is later rejected by the authorization
 * layer).
 */
public class ApiKeyAuthConverter implements ServerAuthenticationConverter {

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String provided = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (provided == null || provided.isBlank()) {
            return Mono.empty();
        }
        return Mono.just(new UsernamePasswordAuthenticationToken(provided, provided));
    }
}

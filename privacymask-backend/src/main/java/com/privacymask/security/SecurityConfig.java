package com.privacymask.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.privacymask.ratelimit.RateLimitProperties;
import com.privacymask.ratelimit.RateLimitWebFilter;
import com.privacymask.ratelimit.TokenBucketRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Spring Security configuration for the PrivacyMask gateway. API-key auth via
 * X-API-Key header. Stateless. CSRF/Basic/Form disabled. Analyze endpoint
 * protected; status endpoint public. Auth occurs before PII processing.
 *
 * <p>Phase 12 adds the rate-limiting filter immediately after authorization:
 * authentication first, rate limiting second, PrivacyMask processing third.
 * Unauthenticated requests are rejected with 401 before they can reach (or
 * consume quota from) the limiter.</p>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final ApiKeyProperties apiKeyProperties;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties rateLimitProperties;
    private final TokenBucketRateLimiter rateLimiter;
    private final CorsProperties corsProperties;

    public SecurityConfig(ApiKeyProperties apiKeyProperties,
                          ObjectMapper objectMapper,
                          RateLimitProperties rateLimitProperties,
                          TokenBucketRateLimiter rateLimiter,
                          CorsProperties corsProperties) {
        this.apiKeyProperties = apiKeyProperties;
        this.objectMapper = objectMapper;
        this.rateLimitProperties = rateLimitProperties;
        this.rateLimiter = rateLimiter;
        this.corsProperties = corsProperties;
    }

    /**
     * CORS source for browser-based callers. With no origins configured the
     * {@link CorsConfiguration} carries no allowed origins, so Spring emits
     * no CORS headers and browsers stay blocked. Development opts in with
     * exact origins only (e.g. the Vite dev server); {@code *} is never
     * used and credentials are never allowed.
     */
    @Bean
    public CorsConfigurationSource privacyMaskCorsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = corsProperties.origins();
        if (!origins.isEmpty()) {
            configuration.setAllowedOrigins(origins);
            configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-API-Key"));
            configuration.setExposedHeaders(List.of("Retry-After"));
            configuration.setAllowCredentials(false);
            configuration.setMaxAge(3600L);
        }
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        ApiKeyAuthConverter converter = new ApiKeyAuthConverter();
        ApiKeyAuthManager authManager = new ApiKeyAuthManager(apiKeyProperties);
        StatelessInMemorySecurityContextRepository repository =
                new StatelessInMemorySecurityContextRepository();

        AuthenticationWebFilter filter = new AuthenticationWebFilter(authManager);
        filter.setServerAuthenticationConverter(converter);
        // Only attempt authentication for the protected analyze endpoint (all methods).
        filter.setRequiresAuthenticationMatcher(
                ServerWebExchangeMatchers.pathMatchers("/api/v1/privacymask/analyze"));
        // Stateless: keep the authenticated context on the current exchange only.
        filter.setSecurityContextRepository(repository);
        // Route auth failures through the JSON entry point (no redirect/login page).
        filter.setAuthenticationFailureHandler(
                new ServerAuthenticationEntryPointFailureHandler(new JsonAuthenticationEntryPoint(objectMapper)));

        http
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/privacymask/status").permitAll()
                        .pathMatchers("/api/v1/privacymask/analyze").authenticated()
                        .anyExchange().permitAll())
                .securityContextRepository(repository)
                .addFilterAt(filter, SecurityWebFiltersOrder.AUTHENTICATION)
                // Phase 12: rate limiting runs after authentication AND
                // authorization, before the PrivacyMask controller. 401s
                // therefore short-circuit before any quota is touched.
                // The filter is constructed here (not a bean) so it runs
                // exactly once: a WebFilter bean would also be picked up by
                // Boot's main chain and execute twice per request.
                .addFilterAfter(
                        new RateLimitWebFilter(rateLimitProperties, rateLimiter, objectMapper),
                        SecurityWebFiltersOrder.AUTHORIZATION)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint(objectMapper)))
                // Phase 13: opt-in browser CORS for local frontend development.
                // Empty by default (no CORS headers); exact origins only.
                .cors(cors -> cors.configurationSource(privacyMaskCorsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());

        return http.build();
    }

    /**
     * Exchange-scoped {@link ServerSecurityContextRepository}. Successful
     * authentication stays on the current exchange attributes so the same
     * request can be authorized; nothing is written to a session, cookie, or
     * shared store.
     */
    static final class StatelessInMemorySecurityContextRepository
            implements ServerSecurityContextRepository {

        private static final String KEY =
                StatelessInMemorySecurityContextRepository.class.getName() + ".CONTEXT";

        @Override
        public Mono<Void> save(ServerWebExchange exchange, SecurityContext context) {
            exchange.getAttributes().put(KEY, context);
            return Mono.empty();
        }

        @Override
        public Mono<SecurityContext> load(ServerWebExchange exchange) {
            Object stored = exchange.getAttributes().get(KEY);
            if (stored instanceof SecurityContext context) {
                return Mono.just(context);
            }
            // Nothing stored on this exchange: return empty (like the default
            // NoOp repository). Returning an empty SecurityContextImpl here
            // would break downstream: SecurityContextServerWebExchange maps
            // SecurityContext::getAuthentication, which is null for an empty
            // context, producing an NPE (HTTP 500) instead of the intended
            // HTTP 401 for missing/blank credentials.
            return Mono.empty();
        }
    }
}

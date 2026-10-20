package com.example.signer.server.security;

import com.example.signer.server.auth.JwtService;
import com.example.signer.server.auth.UserAccountService;
import com.example.signer.server.invites.InvitationService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.time.Clock;

/**
 * Spring Security for the coordination server (security Phase 2).
 *
 * <p>Default: every request needs an account access token
 * ({@code ROLE_USER}). The exceptions are listed in {@link #PUBLIC} and
 * the workflow API, which also admits guest signing sessions (limited
 * further by {@link AuthenticatedRequestFilter}). Anything not matched is
 * refused, so a new endpoint is protected until someone opens it here.
 *
 * <p>Stateless: no HTTP session, no cookies, so no CSRF token is needed;
 * a browser website (Phase 6) must revisit CSRF if it uses cookies.
 *
 * <p>Only the coordination server is a servlet application; the desktop
 * context never loads this configuration.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    static final String ROLE_USER = "ROLE_USER";
    static final String ROLE_GUEST = "ROLE_GUEST";

    /**
     * Requests that need no token. A bearer header sent to them is ignored
     * (not validated), so a stale access token can never block a sign-in,
     * refresh or logout.
     */
    static final RequestMatcher PUBLIC = new OrRequestMatcher(
            post("/api/auth/login"),
            post("/api/auth/refresh"),
            post("/api/auth/logout"),
            post("/api/auth/register"),
            // Invitation link: preview and opening a guest signing session.
            // The invitation token in the path is checked by the service.
            get("/api/invitations/*"),
            post("/api/invitations/*/guest-session"),
            // Pages behind the emailed link.
            get("/invitations/*"),
            get("/sign/*"),
            get("/sign/*/document"),
            // Spring's error dispatch, so a failure reports its real status.
            path("/error"));

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
            UserAccountService accounts, ObjectProvider<InvitationService> invitations,
            @Value("${signature.remote-server.login.rate-limit:20}") int rateLimit,
            @Value("${signature.remote-server.login.rate-window-seconds:60}") long rateWindow)
            throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .anonymous(a -> { })
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(JsonSecurityErrors.entryPoint())
                    .accessDeniedHandler(JsonSecurityErrors.accessDenied()))
            .oauth2ResourceServer(rs -> rs
                    .bearerTokenResolver(bearerTokens())
                    .authenticationEntryPoint(JsonSecurityErrors.entryPoint())
                    .accessDeniedHandler(JsonSecurityErrors.accessDenied())
                    .jwt(jwt -> jwt
                            .decoder(jwtService.decoder())
                            .jwtAuthenticationConverter(new TokenAuthenticationConverter())))
            .addFilterBefore(new LoginRateLimitFilter(rateLimit, rateWindow, Clock.systemUTC()),
                    BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new AuthenticatedRequestFilter(accounts, invitations),
                    BearerTokenAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                    .requestMatchers(PUBLIC).permitAll()
                    .requestMatchers(path("/api/workflows/**"))
                            .hasAnyAuthority(ROLE_USER, ROLE_GUEST)
                    .requestMatchers(path("/api/**")).hasAuthority(ROLE_USER)
                    .anyRequest().denyAll());
        return http.build();
    }

    /** Standard {@code Authorization: Bearer} only (no query or form tokens); none on public routes. */
    private static BearerTokenResolver bearerTokens() {
        DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) ->
                PUBLIC.matches(request) ? null : standard.resolve(request);
    }

    private static RequestMatcher path(String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(pattern);
    }

    private static RequestMatcher get(String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, pattern);
    }

    private static RequestMatcher post(String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, pattern);
    }
}

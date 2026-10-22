package com.example.signer.server.security;

import com.example.signer.server.auth.AccessTokenDenyList;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.auth.UserAccountService;
import com.example.signer.server.web.FrontendRoutingConfiguration;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.launch.LaunchCodeService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
    /** Phase B: desktop task session from a website launch code. */
    static final String ROLE_TASK = "ROLE_TASK";

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
            post("/api/invitations/*/guest-code"),
            post("/api/invitations/*/guest-session"),
            // Phase B: the launch code in the body is the credential.
            post("/api/desktop/launch"),
            // Reachability only (no sensitive data).
            get("/api/health"),
            // Pages behind the emailed link.
            get("/invitations/*"),
            get("/sign/*"),
            get("/sign/*/document"),
            // The website, served from this application's own origin (D2).
            // Named one by one rather than as a wildcard: this list is what
            // needs no token, so anything added to it is a decision. The
            // chain below ends in denyAll(), which is why a static file has
            // to be listed at all.
            get("/"),
            get("/index.html"),
            get("/favicon.ico"),
            get("/assets/**"),
            get(FrontendRoutingConfiguration.VERIFY_EMAIL_PAGE),
            get(FrontendRoutingConfiguration.RESET_PASSWORD_PAGE),
            // Spring's error dispatch, so a failure reports its real status.
            path("/error"));

    /**
     * The per-address sign-in limiter, as a SHARED singleton: security Phase C
     * adds a second filter chain for the website, and both chains must count
     * against the same budget. Two instances would mean two independent maps,
     * so a flood could get twice the attempts simply by alternating between
     * {@code /api/auth/login} and {@code /web/api/auth/login}.
     *
     * <p>The store behind it decides whether one budget also spans several
     * server instances and survives a restart (risk C6, with D3): a shared
     * database table when there is a database, the heap when there is not.
     * This chain is configured with or without one, which is why the store is
     * injected rather than created here.
     */
    @Bean
    LoginRateLimitFilter loginRateLimitFilter(
            @Value("${signature.remote-server.login.rate-limit:20}") int rateLimit,
            @Value("${signature.remote-server.login.rate-window-seconds:60}") long rateWindow,
            RateLimitStore rateLimitStore) {
        return new LoginRateLimitFilter(rateLimit, rateWindow, rateLimitStore);
    }

    /**
     * The bearer chain. Ordered AFTER the website chain
     * ({@link WebSecurityConfig}) because this one matches any request: Spring
     * Security refuses a configuration where a catch-all chain is not last.
     */
    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
            UserAccountService accounts, AccessTokenDenyList deniedTokens,
            ObjectProvider<InvitationService> invitations,
            ObjectProvider<LaunchCodeService> launches,
            LoginRateLimitFilter loginRateLimitFilter)
            throws Exception {
        http
            // Risk L9 (and C7/D10): no Referer carrying a one-time token out
            // of an invitation page, and a policy that permits no script.
            .headers(SecurityHeaders::apply)
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
            .addFilterBefore(loginRateLimitFilter,
                    BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new AuthenticatedRequestFilter(accounts, deniedTokens,
                            invitations, launches),
                    BearerTokenAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                    .requestMatchers(PUBLIC).permitAll()
                    .requestMatchers(path("/api/workflows/**"))
                            .hasAnyAuthority(ROLE_USER, ROLE_GUEST, ROLE_TASK)
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

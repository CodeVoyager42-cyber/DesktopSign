package com.example.signer.server.security;

import com.example.signer.server.auth.UserAccountService;
import com.example.signer.server.web.WebSessionProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.time.Duration;

/**
 * Spring Security for the website (security Phase C).
 *
 * <p>This chain is ordered <b>before</b> {@link SecurityConfig} and claims
 * {@code /web/**} with a {@code securityMatcher}; everything else falls
 * through to the existing stateless bearer chain, whose rules are unchanged.
 *
 * <p><b>The two domains never authenticate each other's routes:</b>
 * <ul>
 *   <li>Here there is no {@code oauth2ResourceServer} and no bearer token
 *       resolver at all, so an {@code Authorization: Bearer …} header on
 *       {@code /web/**} is never even parsed — a desktop or partner JWT
 *       cannot open a website route, and the request is answered 401.</li>
 *   <li>{@link SecurityConfig} is {@code STATELESS}, so its security-context
 *       repository never consults an {@code HttpSession} — the website
 *       cookie on {@code /api/**} authenticates nothing and the request is
 *       answered 401.</li>
 * </ul>
 *
 * <p>Credential: the server-side session cookie only
 * ({@code com.example.signer.server.web.WebSessionConfiguration}). CSRF is
 * enforced on every state-changing request with a double-submit token, and
 * the session cookie is additionally {@code SameSite=Lax}.
 *
 * <p>Active only for the coordination server in database mode, because the
 * session store is PostgreSQL. In file mode {@code /web/**} is not claimed
 * here and falls through to {@code SecurityConfig}'s {@code denyAll} — the
 * website is unavailable rather than unprotected.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class WebSecurityConfig {

    /** Authority of a signed-in website visitor. Deliberately not {@code ROLE_USER}. */
    public static final String ROLE_WEB_USER = "ROLE_WEB_USER";

    /** Name of the header the website sends the CSRF token in. */
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";
    /** Name of the (script-readable) cookie the CSRF token is published in. */
    public static final String CSRF_COOKIE = "XSRF-TOKEN";

    /**
     * Where the authenticated identity is stored between requests. Exposed as
     * a bean so the login endpoint can save a context into the NEW session it
     * creates (Spring Security 6 requires an explicit save).
     */
    @Bean
    public SecurityContextRepository webSecurityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * The CSRF token store: held in the session, mirrored into a readable
     * {@code XSRF-TOKEN} cookie the website echoes in
     * {@value #CSRF_HEADER}. See
     * {@link SessionBackedCsrfTokenRepository} for why the session is the
     * authority rather than the cookie alone.
     *
     * <p>Exposed as a bean so the login endpoint can rotate the token on a
     * successful sign-in, and so the cookie carries the same {@code Secure}
     * flag as the session cookie.
     */
    @Bean
    public CsrfTokenRepository webCsrfTokenRepository(WebSessionProperties properties) {
        return new SessionBackedCsrfTokenRepository(properties.isSecureCookie());
    }

    /**
     * The sign-up throttle (risk D1). Its own bean so the limits are
     * configurable and the filter is testable on its own; the window is an
     * hour rather than the shared limiter's minute, because minutes are the
     * wrong unit for creating accounts.
     */
    @Bean
    SignUpThrottleFilter signUpThrottleFilter(
            @Value("${signature.remote-server.web.signup-limit:5}") int signUpLimit,
            @Value("${signature.remote-server.web.signup-window:PT1H}") Duration signUpWindow,
            RateLimitStore rateLimitStore) {
        return new SignUpThrottleFilter(signUpLimit, signUpWindow, rateLimitStore);
    }

    @Bean
    @Order(1)
    SecurityFilterChain webSecurityFilterChain(HttpSecurity http,
            SecurityContextRepository webSecurityContextRepository,
            CsrfTokenRepository webCsrfTokenRepository,
            LoginRateLimitFilter loginRateLimit,
            SignUpThrottleFilter signUpThrottle,
            DownloadRateLimitFilter downloadRateLimit,
            UserAccountService accounts,
            WebSessionProperties sessionProperties) throws Exception {
        http
            .securityMatcher(path("/web/**"))
            // The same two headers as the other chain, from the same place:
            // risks L9 and C7/D10. D2 will have to widen the policy when
            // there are scripts and styles of its own to serve.
            .headers(SecurityHeaders::apply)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            // Logout is a JSON endpoint of our own, so Spring's HTML-oriented
            // logout filter is off: it would answer with a redirect.
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .anonymous(a -> { })
            .securityContext(c -> c.securityContextRepository(webSecurityContextRepository))
            .sessionManagement(s -> s
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    // Belt and braces: the login endpoint also invalidates the
                    // old session and creates a new one, so the id a visitor
                    // arrived with is never the id they end up authenticated on.
                    .sessionFixation(f -> f.changeSessionId()))
            .csrf(c -> c
                    // Token held in the session, mirrored into a readable
                    // cookie. The PLAIN request handler is deliberate —
                    // Spring Security's default XOR handler masks the token
                    // per response, so the cookie value would not match the
                    // header the website echoes back.
                    .csrfTokenRepository(webCsrfTokenRepository)
                    .csrfTokenRequestHandler(plainCsrfHandler()))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(JsonSecurityErrors.entryPoint())
                    .accessDeniedHandler(JsonSecurityErrors.accessDenied()))
            // Shared instance, so a flood against /web/api/auth/login counts
            // against the very same per-address budget as /api/auth/login.
            .addFilterBefore(loginRateLimit, CsrfFilter.class)
            // D1: a far tighter, hour-long budget on sign-up alone. Before
            // the shared limiter, so a sign-up flood is turned away without
            // spending the budget the other account routes share.
            .addFilterBefore(signUpThrottle, LoginRateLimitFilter.class)
            // The same shared limiter the /downloads chain uses, so one
            // budget covers both routes.
            .addFilterBefore(downloadRateLimit, LoginRateLimitFilter.class)
            // Both of these run with the security context already loaded from
            // the session and before the URL rules. Chained explicitly rather
            // than anchored on the same filter twice, so their order is not
            // left to insertion order.
            .addFilterAfter(new AbsoluteSessionTimeoutFilter(
                    sessionProperties.getAbsoluteTimeout()), CsrfFilter.class)
            // C3: a session must not outlive the account it belongs to.
            .addFilterAfter(new WebAccountStatusFilter(accounts),
                    AbsoluteSessionTimeoutFilter.class)
            .authorizeHttpRequests(a -> a
                    // Bootstrap: hands out the CSRF cookie. Safe method.
                    .requestMatchers(get("/web/api/csrf")).permitAll()
                    // Sign-in and sign-out need no session yet, but DO need a
                    // CSRF token (both are state-changing POSTs).
                    .requestMatchers(post("/web/api/auth/login")).permitAll()
                    .requestMatchers(post("/web/api/auth/logout")).permitAll()
                    // Phase D account self-service. These are reached by
                    // someone who cannot sign in yet — they have no account,
                    // have not verified it, or have forgotten the password —
                    // so they need no session, only a CSRF token. Each
                    // answers the same whatever the address or token was, so
                    // being open does not make them an oracle.
                    .requestMatchers(post("/web/api/auth/register")).permitAll()
                    .requestMatchers(post("/web/api/auth/verify-email")).permitAll()
                    .requestMatchers(post("/web/api/auth/resend-verification")).permitAll()
                    .requestMatchers(post("/web/api/auth/password-reset/request")).permitAll()
                    .requestMatchers(post("/web/api/auth/password-reset/confirm")).permitAll()
                    // What there is to download, for the /download page.
                    // Public because the page is: an invited signer has no
                    // account yet and still needs the application. It says
                    // only what a release is — name, size, checksum — and
                    // is rate limited per address like the file itself.
                    .requestMatchers(get("/web/api/download-info")).permitAll()
                    // Everything else the website offers needs a session.
                    .requestMatchers(path("/web/api/**")).hasAuthority(ROLE_WEB_USER)
                    // No website pages are served yet (Phase D), so nothing
                    // else under /web is open.
                    .anyRequest().denyAll());
        return http.build();
    }

    /**
     * Plain (unmasked) CSRF token handling, with the request attribute
     * disabled so the token is resolved eagerly and the cookie is written on
     * the bootstrap request rather than only once something reads it.
     */
    private static CsrfTokenRequestAttributeHandler plainCsrfHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
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

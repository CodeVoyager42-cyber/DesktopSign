package com.example.signer.server.web;

import com.example.signer.server.auth.AuthController;
import com.example.signer.server.auth.LogSafe;
import com.example.signer.server.auth.UserAccountService;
import com.example.signer.server.security.WebSecurityConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Website authentication (security Phase C). JSON in, JSON out, no redirects.
 *
 * <p>The credential is a server-side session cookie and nothing else. No
 * token of any kind is returned to the browser, so there is nothing for the
 * website to keep in {@code localStorage}.
 *
 * <p>Password checking, per-account lockout and the per-address rate limit
 * are the SAME as {@code /api/auth/login}: one {@link UserAccountService},
 * one bcrypt cost, one shared {@code LoginRateLimitFilter}. There is no
 * second user store and no second lockout counter.
 *
 * <p>Enumeration: a wrong password, an unknown user, a disabled account and a
 * locked account all produce the identical 401 body, and the log records only
 * a fingerprint of the identifier, never the typed text.
 */
@RestController
@RequestMapping("/web/api")
// BOTH conditions, and they must match WebSecurityConfig's exactly: this
// controller depends on a bean that configuration declares. With only the
// database condition the controller would also be created in a non-servlet
// context — the desktop, or any `webEnvironment = NONE` test — where the
// servlet-only configuration is absent and its SecurityContextRepository
// cannot be injected, failing the whole context.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class WebAuthController {

    private static final Logger log = LoggerFactory.getLogger(WebAuthController.class);

    /** The one answer every failed sign-in gets, whatever the real reason. */
    private static final ErrorResponse BAD_CREDENTIALS = new ErrorResponse(
            "AUTHENTICATION_FAILED", "Wrong e-mail, username or password.");

    private final UserAccountService accounts;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final WebSessionProperties sessionProperties;

    public WebAuthController(UserAccountService accounts,
                             SecurityContextRepository webSecurityContextRepository,
                             CsrfTokenRepository webCsrfTokenRepository,
                             WebSessionProperties sessionProperties) {
        this.accounts = accounts;
        this.securityContextRepository = webSecurityContextRepository;
        this.csrfTokenRepository = webCsrfTokenRepository;
        this.sessionProperties = sessionProperties;
    }

    /**
     * Bootstrap: publishes the CSRF cookie so the website can echo it back in
     * {@value WebSecurityConfig#CSRF_HEADER}. Reading the token is what makes
     * the repository write the cookie, hence the otherwise pointless call.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) {
        // Always (re-)publish the cookie. The token lives in the session, so a
        // visitor whose session already holds one would otherwise get no
        // cookie back and have no way to read the value it must echo.
        //
        // The token is read from the repository rather than taken as a
        // resolved CsrfToken argument on purpose: Spring MVC hands over a
        // deferred supplier wrapper, and storing THAT in the session fails to
        // serialize — the session store writes attributes as bytes.
        CsrfToken current = csrfTokenRepository.loadToken(httpRequest);
        if (current == null) {
            current = csrfTokenRepository.generateToken(httpRequest);
        }
        csrfTokenRepository.saveToken(current, httpRequest, httpResponse);
        capAnonymousSession(httpRequest);
        return ResponseEntity.noContent().build();
    }

    /**
     * Password sign-in. On success the OLD session is invalidated and a NEW
     * one created, so the session id a visitor arrived holding can never be
     * the id they end up authenticated on (session fixation).
     */
    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        // The client address goes with the credentials (Phase 2 risk L3), and
        // it is the same (account, address) pair the desktop API counts
        // against: the two sign-in routes check the same passwords against the
        // same accounts, so they must not be two independent budgets — the
        // same reasoning that makes the rate-limit filter a shared singleton.
        if (request == null || request.username() == null || request.password() == null
                || !accounts.matches(request.username(), request.password(),
                        AuthController.clientAddress(httpRequest))) {
            log.info("Website sign-in failed for identifier {}",
                    LogSafe.fingerprint(request == null ? null : request.username()));
            return ResponseEntity.status(401).body(BAD_CREDENTIALS);
        }

        // The identity is the ACCOUNT, never the typed string: signing in with
        // an e-mail must authenticate as the account that owns it.
        String userId = accounts.accountId(request.username());
        if (!accounts.isRegistered(userId)) {
            // Same answer as a wrong password: an invited-but-unregistered
            // placeholder must not be distinguishable from a bad credential.
            log.info("Website sign-in refused: account '{}' cannot sign in", userId);
            return ResponseEntity.status(401).body(BAD_CREDENTIALS);
        }

        rotateSession(httpRequest);
        // Signing in promotes the session from the short anonymous idle
        // timeout to the full one (Phase C risk C9).
        HttpSession signedIn = httpRequest.getSession();
        signedIn.setMaxInactiveInterval((int) sessionProperties.getIdleTimeout().toSeconds());
        rotateCsrfToken(httpRequest, httpResponse);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userId, null,
                AuthorityUtils.createAuthorityList(WebSecurityConfig.ROLE_WEB_USER));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Spring Security 6 does not save the context for us.
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        accounts.onLoginSuccess(userId);
        log.info("Website session opened for '{}'", userId);
        return ResponseEntity.ok(toProfile(userId));
    }

    /**
     * Sign out: invalidates the session server-side, so the row is deleted
     * and the cookie the browser still holds authenticates nothing.
     * Idempotent — signing out twice, or without a session, is a 204.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
            log.info("Website session invalidated (sign-out)");
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /** The signed-in account. Nothing secret: username, e-mail, verified flag. */
    @GetMapping("/auth/me")
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        return ResponseEntity.ok(toProfile(authentication.getName()));
    }

    private MeResponse toProfile(String userId) {
        UserAccountService.AccountProfile profile = accounts.profile(userId);
        return new MeResponse(profile.username(), profile.email(), profile.emailVerified());
    }

    /**
     * Drops any pre-login session and starts a fresh one, so the new session
     * id cannot have been chosen or observed by whoever planted the old
     * cookie. Done explicitly because this is a JSON endpoint, not Spring
     * Security's {@code formLogin}, so nothing rotates the id for us.
     */
    private static void rotateSession(HttpServletRequest httpRequest) {
        HttpSession existing = httpRequest.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        httpRequest.getSession(true);
    }

    /**
     * Holds an anonymous session to a much shorter idle timeout than a
     * signed-in one (security Phase C risk C9). The CSRF bootstrap has to
     * create a session for every visitor, so without this a single page load
     * would occupy a row for the full signed-in window.
     */
    private void capAnonymousSession(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a ->
                        WebSecurityConfig.ROLE_WEB_USER.equals(a.getAuthority()));
        if (!signedIn) {
            session.setMaxInactiveInterval(
                    (int) sessionProperties.getAnonymousIdleTimeout().toSeconds());
        }
    }

    /**
     * Issues a fresh CSRF token at sign-in, for the same reason the session id
     * is rotated: the token a visitor arrived with may have been planted by
     * someone else, and after this request it is no longer accepted. The new
     * cookie is on the response, so the website re-reads it as usual.
     */
    private void rotateCsrfToken(HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse) {
        CsrfToken fresh = csrfTokenRepository.generateToken(httpRequest);
        csrfTokenRepository.saveToken(fresh, httpRequest, httpResponse);
        // The CsrfFilter published the OLD token as a request attribute when
        // it validated this request; replace it so anything reading the
        // attribute downstream sees the token that is now in force.
        httpRequest.setAttribute(CsrfToken.class.getName(), fresh);
    }

    /** Body of {@code POST /web/api/auth/login}. */
    public record LoginRequest(String username, String password) {
    }

    /** Answer of {@code /web/api/auth/me} and of a successful sign-in. */
    public record MeResponse(String username, String email, boolean emailVerified) {
    }

    /** The {@code {"code","message"}} shape the rest of the API already uses. */
    public record ErrorResponse(String code, String message) {
    }
}

package com.example.signer.server.security;

import com.example.signer.server.auth.UserAccountService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Re-checks the account behind a website session on EVERY request (security
 * Phase C, residual risk C3).
 *
 * <p>Without this, a session outlives the account it belongs to: disabling or
 * removing an account would stop new sign-ins but leave every session already
 * open working until its idle timeout. The bearer chain has always re-checked
 * on every request ({@link AuthenticatedRequestFilter}); this gives the
 * website the same rule and the same two conditions — the account must still
 * be known and must still be able to sign in.
 *
 * <p>On failure the session is <b>invalidated server-side</b> (its row is
 * deleted, so the cookie the browser still holds is worthless everywhere, not
 * just on this instance), the context is cleared and the request is answered
 * 401.
 *
 * <p>Runs after the security context has been loaded from the session and
 * before the URL rules, so no controller can act on a dead account.
 */
public final class WebAccountStatusFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebAccountStatusFilter.class);

    private final UserAccountService accounts;

    public WebAccountStatusFilter(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (isWebsiteSession(auth) && !canStillSignIn(auth.getName())) {
            log.info("Website session refused: account '{}' can no longer sign in"
                    + " — session invalidated", auth.getName());
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            JsonSecurityErrors.unauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Only a session-authenticated website visitor is checked here. An
     * anonymous request is left to the URL rules, which refuse it anyway.
     */
    private static boolean isWebsiteSession(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (WebSecurityConfig.ROLE_WEB_USER.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** The same two conditions the bearer chain applies to an access token. */
    private boolean canStillSignIn(String userId) {
        return accounts.knowsUser(userId) && accounts.isRegistered(userId);
    }
}

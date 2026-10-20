package com.example.signer.server.security;

import com.example.signer.server.api.ApiErrorType;
import com.example.signer.server.auth.GuestAccessPolicy;
import com.example.signer.server.auth.UserAccountService;
import com.example.signer.server.invites.InvitationService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs after Spring Security has validated the bearer token, and before
 * the URL rules and controllers:
 *
 * <ul>
 *   <li><b>Account token</b>: the account must still be able to sign in.
 *       A disabled or removed account is refused at once (401) instead of
 *       working until its access token expires.</li>
 *   <li><b>Guest token</b>: only the requests in {@link GuestAccessPolicy},
 *       on its own workflow, while its invitation is still active (403
 *       otherwise).</li>
 * </ul>
 *
 * Then it exposes the identity as request attribute {@link #USER_ATTR}
 * (and {@link #GUEST_ATTR} for guests), which controllers read through
 * {@code AuthController.requireUser}.
 */
public final class AuthenticatedRequestFilter extends OncePerRequestFilter {

    /** Request attribute carrying the authenticated user id. */
    public static final String USER_ATTR = "auth.user";
    /** Request attribute set only for guest signing sessions. */
    public static final String GUEST_ATTR = "auth.guest";

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedRequestFilter.class);

    private final UserAccountService accounts;
    private final ObjectProvider<InvitationService> invitations;

    public AuthenticatedRequestFilter(UserAccountService accounts,
                                      ObjectProvider<InvitationService> invitations) {
        this.accounts = accounts;
        this.invitations = invitations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof GuestSessionAuthentication guest) {
            if (!GuestAccessPolicy.allows(request.getMethod(), request.getRequestURI(),
                    guest.guest())) {
                log.info("Guest session refused {} {} (outside its signing task)",
                        request.getMethod(), request.getRequestURI());
                JsonSecurityErrors.forbidden(response, "WORKFLOW_NOT_ACCESSIBLE",
                        "This signing link only gives access to its own signing task");
                return;
            }
            InvitationService service = invitations.getIfAvailable();
            if (service == null) {
                JsonSecurityErrors.unauthorized(response);
                return;
            }
            try {
                service.requireGuestSessionActive(guest.guest());
            } catch (ApiErrorType.ApiException e) {
                JsonSecurityErrors.forbidden(response, e.code().name(), e.getMessage());
                return;
            }
            request.setAttribute(USER_ATTR, guest.guest().principal());
            request.setAttribute(GUEST_ATTR, guest.guest());
        } else if (auth instanceof JwtAuthenticationToken user) {
            String userId = user.getName();
            if (!accounts.knowsUser(userId) || !accounts.isRegistered(userId)) {
                log.info("Access token refused: account '{}' can no longer sign in", userId);
                SecurityContextHolder.clearContext();
                JsonSecurityErrors.unauthorized(response);
                return;
            }
            request.setAttribute(USER_ATTR, userId);
        }
        chain.doFilter(request, response);
    }
}

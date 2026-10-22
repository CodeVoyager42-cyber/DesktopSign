package com.example.signer.server.security;

import com.example.signer.server.api.ApiErrorType;
import com.example.signer.server.auth.GuestAccessPolicy;
import com.example.signer.server.auth.SigningActionPolicy;
import com.example.signer.server.auth.TaskAccessPolicy;
import com.example.signer.server.auth.UserAccountService;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.launch.LaunchCodeService;
import com.example.signer.server.workflow.RemoteWorkflowService;

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
 *       working until its access token expires, and so is a token issued
 *       before the account's password changed — Phase D1 risk D7. For the
 *       requests in {@link SigningActionPolicy} it must also have a verified
 *       e-mail address (403 otherwise) — Phase D1 risk D4.</li>
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
    private final ObjectProvider<LaunchCodeService> launches;

    public AuthenticatedRequestFilter(UserAccountService accounts,
                                      ObjectProvider<InvitationService> invitations,
                                      ObjectProvider<LaunchCodeService> launches) {
        this.accounts = accounts;
        this.invitations = invitations;
        this.launches = launches;
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
        } else if (auth instanceof TaskSessionAuthentication taskSession) {
            // Phase B: the launch code's single task, while the code is not
            // revoked and the account can still sign in.
            JwtService.TaskAuthentication task = taskSession.task();
            // Its own allow-list, not the guest one: no certificate binding.
            if (!TaskAccessPolicy.allows(request.getMethod(), request.getRequestURI(),
                    task.workflowId())) {
                log.info("Desktop task session refused {} {} (outside its signing task)",
                        request.getMethod(), request.getRequestURI());
                JsonSecurityErrors.forbidden(response, "WORKFLOW_NOT_ACCESSIBLE",
                        "This signing session only gives access to its own signing task");
                return;
            }
            LaunchCodeService service = launches.getIfAvailable();
            RemoteWorkflowService.TaskSlotState state = service == null
                    || !accounts.knowsUser(task.principal())
                    || !accounts.isRegistered(task.principal())
                    // Phase D1 risk D7, and the longer half of it: a task
                    // session lives 30 minutes, twice an access token, so
                    // leaving it out would have left a bigger window than
                    // the one being closed.
                    || accounts.passwordChangedSince(task.principal(),
                            taskSession.getToken().getIssuedAt())
                    ? RemoteWorkflowService.TaskSlotState.INVALID
                    : service.taskSessionState(task);
            if (state == RemoteWorkflowService.TaskSlotState.INVALID) {
                log.info("Desktop task session refused: revoked, or the account can no"
                        + " longer sign in");
                SecurityContextHolder.clearContext();
                JsonSecurityErrors.unauthorized(response);
                return;
            }
            if (state == RemoteWorkflowService.TaskSlotState.FINISHED
                    && !TaskAccessPolicy.isStatusRead(request.getMethod(),
                            request.getRequestURI(), task.workflowId())) {
                log.info("Desktop task session refused {} {} (task finished)",
                        request.getMethod(), request.getRequestURI());
                JsonSecurityErrors.forbidden(response, "TASK_SESSION_FINISHED",
                        "This signing task is finished. Open it from the SignFlow website"
                                + " to see the result.");
                return;
            }
            request.setAttribute(USER_ATTR, task.principal());
        } else if (auth instanceof JwtAuthenticationToken user) {
            String userId = user.getName();
            if (!accounts.knowsUser(userId) || !accounts.isRegistered(userId)) {
                log.info("Access token refused: account '{}' can no longer sign in", userId);
                SecurityContextHolder.clearContext();
                JsonSecurityErrors.unauthorized(response);
                return;
            }
            // Phase D1 risk D7: a reset or a change revokes the sessions and
            // the refresh tokens, but an access token is a signed JWT —
            // nothing is stored for it, so nothing could be deleted. This is
            // where it ends instead.
            if (accounts.passwordChangedSince(userId, user.getToken().getIssuedAt())) {
                log.info("Access token refused: issued before the password of account"
                        + " '{}' changed", userId);
                SecurityContextHolder.clearContext();
                JsonSecurityErrors.unauthorized(response);
                return;
            }
            // Phase D1 risk D4: an unverified address may sign in and read,
            // but not commit the account's name to a document.
            if (SigningActionPolicy.requiresVerifiedEmail(request.getMethod(),
                    request.getRequestURI()) && !accounts.maySign(userId)) {
                log.info("Signing action refused for '{}': the account's e-mail address"
                        + " is not verified", userId);
                JsonSecurityErrors.forbidden(response, "EMAIL_NOT_VERIFIED",
                        "Verify your e-mail address before signing documents");
                return;
            }
            request.setAttribute(USER_ATTR, userId);
        }
        chain.doFilter(request, response);
    }
}

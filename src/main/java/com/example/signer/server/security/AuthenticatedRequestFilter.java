package com.example.signer.server.security;

import com.example.signer.server.auth.AccessTokenDenyList;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.auth.TaskAccessPolicy;
import com.example.signer.server.auth.SigningActionPolicy;
import com.example.signer.server.auth.UserAccountService;
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
 *   <li><b>Account token</b>: the token must not have been signed out of
 *       (401 — Phase 2 risk L4), and the account must still be able to sign
 *       in. A disabled or removed account is refused at once (401) instead of
 *       working until its access token expires, and so is a token issued
 *       before the account's password changed — Phase D1 risk D7. For the
 *       requests in {@link SigningActionPolicy} it must also have a verified
 *       e-mail address (403 otherwise) — Phase D1 risk D4.</li>
 * </ul>
 *
 * <p>There were two more kinds: a <b>guest token</b> scoped to one invited
 * signing task, and a <b>task token</b> from a desktop launch code. Both
 * workflows are removed, so an account access token is the only identity
 * that reaches here — which is why there is one branch and no attribute
 * distinguishing them any more.
 *
 * Then it exposes the identity as request attribute {@link #USER_ATTR},
 * which controllers read through {@code AuthController.requireUser}.
 */
public final class AuthenticatedRequestFilter extends OncePerRequestFilter {

    /** Request attribute carrying the authenticated user id. */
    public static final String USER_ATTR = "auth.user";

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedRequestFilter.class);

    private final UserAccountService accounts;
    private final AccessTokenDenyList deniedTokens;
    private final ObjectProvider<LaunchCodeService> launches;

    public AuthenticatedRequestFilter(UserAccountService accounts,
                                      AccessTokenDenyList deniedTokens,
                                      ObjectProvider<LaunchCodeService> launches) {
        this.accounts = accounts;
        this.deniedTokens = deniedTokens;
        this.launches = launches;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Two kinds of caller reach here: a desktop task session from a
        // launch code, and an account access token. The guest-session branch
        // that used to sit beside them went with the invitation workflow, and
        // GUEST_ATTR with it.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof TaskSessionAuthentication taskSession) {
            JwtService.TaskAuthentication task = taskSession.task();
            // Its own allow-list: a task session may do its one signing task
            // and nothing else — in particular it cannot bind a certificate,
            // so a stolen launch code cannot attach another card to the slot.
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
                    // Risk D7, and the longer half of it: a task session
                    // lives 30 minutes, twice an access token, so leaving it
                    // out would leave a bigger window than the one closed.
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
            // Phase 2 risk L4, and first, before the account is even looked
            // up: a token that has been signed out of is finished, whatever
            // the account behind it is doing.
            String tokenId = user.getToken().getId();
            if (tokenId == null || tokenId.isBlank()) {
                // Every token this server issues carries a jti. One without
                // it could never be denied, so a deny-list that accepted it
                // would be one field away from being bypassable.
                log.info("Access token refused: no jti, so it could never be signed out of");
                SecurityContextHolder.clearContext();
                JsonSecurityErrors.unauthorized(response);
                return;
            }
            if (deniedTokens.isDenied(tokenId)) {
                log.info("Access token refused: signed out of");
                SecurityContextHolder.clearContext();
                JsonSecurityErrors.unauthorized(response);
                return;
            }
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

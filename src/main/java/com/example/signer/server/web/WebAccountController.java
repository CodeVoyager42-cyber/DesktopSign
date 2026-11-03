package com.example.signer.server.web;

import com.example.signer.server.api.ApiErrorType.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Website account self-service endpoints (security Phase D). Session + CSRF
 * chain from Phase C; the three that send e-mail need no session.
 *
 * <p><b>Every answer here is deliberately uninformative.</b> Sign-up, resend
 * and reset-request answer {@code 202} whatever happened — taken address,
 * unknown address, already verified, over the limit. Verification and reset
 * confirmation answer {@code 204} or one single {@code 400}; they never say
 * whether a token was unknown, expired or already used. The only thing a
 * caller can learn is whether their own password met the rules.
 *
 * <p>All six are rate-limited per client address by {@code LoginRateLimitFilter}
 * (their own bucket) and per recipient inside {@link WebAccountService}.
 */
@RestController
@RequestMapping("/web/api")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class WebAccountController {

    /** The single answer every bad or stale one-time link gets. */
    private static final ErrorBody LINK_REFUSED = new ErrorBody("LINK_INVALID",
            "That link is no longer usable. Request a new one.");

    private static final ErrorBody WRONG_PASSWORD = new ErrorBody("AUTHENTICATION_FAILED",
            "The current password is wrong.");

    private final WebAccountService accounts;

    public WebAccountController(WebAccountService accounts) {
        this.accounts = accounts;
    }

    /**
     * Sign-up. Always 202: a taken address is not reported, because that
     * would turn this endpoint into a way to test who has an account.
     */
    @PostMapping("/auth/register")
    public ResponseEntity<?> register(@RequestBody(required = false) RegisterRequest request) {
        if (request == null) {
            return accepted();
        }
        try {
            this.accounts.register(request.email(), request.password(), request.displayName());
        } catch (ApiException weakPassword) {
            // The password rules describe the REQUEST, not who exists, so
            // reporting them leaks nothing.
            throw weakPassword;
        }
        return accepted();
    }

    /** Confirms an address. 204, or one generic 400. */
    @PostMapping("/auth/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestBody(required = false) TokenRequest request) {
        boolean verified = request != null && this.accounts.verifyEmail(request.token());
        return verified ? ResponseEntity.noContent().build()
                : ResponseEntity.badRequest().body(LINK_REFUSED);
    }

    /** Sends another verification link. Always 202. */
    @PostMapping("/auth/resend-verification")
    public ResponseEntity<?> resendVerification(
            @RequestBody(required = false) EmailRequest request) {
        if (request != null) {
            this.accounts.resendVerification(request.email());
        }
        return accepted();
    }

    /** Asks for a password-reset link. Always 202. */
    @PostMapping("/auth/password-reset/request")
    public ResponseEntity<?> requestPasswordReset(
            @RequestBody(required = false) EmailRequest request) {
        if (request != null) {
            this.accounts.requestPasswordReset(request.email());
        }
        return accepted();
    }

    /**
     * Sets a new password from a reset link. 204, or one generic 400 — except
     * a password that breaks the rules, which is reported so the user can fix
     * it.
     */
    @PostMapping("/auth/password-reset/confirm")
    public ResponseEntity<?> confirmPasswordReset(
            @RequestBody(required = false) ResetRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(LINK_REFUSED);
        }
        boolean changed = this.accounts.confirmPasswordReset(
                request.token(), request.newPassword());
        return changed ? ResponseEntity.noContent().build()
                : ResponseEntity.badRequest().body(LINK_REFUSED);
    }

    /**
     * Changes the signed-in user's own password. Needs the current one, and
     * ends every other session of the account.
     */
    @PostMapping("/me/password")
    public ResponseEntity<?> changePassword(@RequestBody(required = false) ChangeRequest request,
                                            Authentication authentication,
                                            HttpServletRequest httpRequest) {
        if (request == null) {
            return ResponseEntity.status(401).body(WRONG_PASSWORD);
        }
        HttpSession session = httpRequest.getSession(false);
        String keep = session == null ? null : session.getId();
        boolean changed = this.accounts.changePassword(authentication.getName(),
                request.currentPassword(), request.newPassword(), keep);
        return changed ? ResponseEntity.noContent().build()
                : ResponseEntity.status(401).body(WRONG_PASSWORD);
    }

    private static ResponseEntity<Void> accepted() {
        return ResponseEntity.accepted().build();
    }

    /** Body of {@code POST /web/api/auth/register}. */
    public record RegisterRequest(String email, String password, String displayName) {
    }

    /** Body of the two endpoints that take only an address. */
    public record EmailRequest(String email) {
    }

    /** Body of {@code POST /web/api/auth/verify-email}. */
    public record TokenRequest(String token) {
    }

    /** Body of {@code POST /web/api/auth/password-reset/confirm}. */
    public record ResetRequest(String token, String newPassword) {
    }

    /** Body of {@code POST /web/api/me/password}. */
    public record ChangeRequest(String currentPassword, String newPassword) {
    }

    /** The {@code {"code","message"}} shape the rest of the API uses. */
    public record ErrorBody(String code, String message) {
    }
}

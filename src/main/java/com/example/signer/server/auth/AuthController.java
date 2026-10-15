package com.example.signer.server.auth;

import com.example.signer.server.api.ApiContracts.LoginRequest;
import com.example.signer.server.api.ApiContracts.MeResponse;
import com.example.signer.server.api.ApiContracts.TokenPairResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Authentication endpoints (Phase 3/4): password login issues a short-lived
 * JWT access token plus a rotating refresh token; {@code /refresh} rotates
 * the refresh token (reuse is detected and kills the session family);
 * {@code /logout} revokes the presented refresh session.
 *
 * <p>Secret-handling: passwords and tokens are never logged. Bad credentials
 * return the same structured 401 for unknown user and wrong password.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserAccountService accounts;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokens;

    public AuthController(UserAccountService accounts,
                          JwtService jwtService,
                          RefreshTokenStore refreshTokens) {
        this.accounts = accounts;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
    }

    /** Password login → access JWT + refresh token. */
    @PostMapping("/login")
    public ResponseEntity<TokenPairResponse> login(@RequestBody LoginRequest request) {
        if (request == null || request.username() == null
                || request.password() == null
                || !accounts.matches(request.username(), request.password())) {
            log.info("Login failed for user '{}'",
                    request == null ? "?" : request.username());
            return unauthorized();
        }
        // The authenticated identity is the ACCOUNT, not the typed string:
        // signing in with an email must authenticate as the account that owns
        // it, otherwise every workflow lookup would use the email as a user id.
        String userId = accounts.accountId(request.username());
        accounts.onLoginSuccess(userId);
        String refresh = refreshTokens.issue(userId);
        log.info("User '{}' logged in", userId);
        return ResponseEntity.ok(new TokenPairResponse(
                jwtService.issueAccessJwt(userId),
                jwtService.accessTtlSeconds(),
                refresh));
    }

    /** Refresh-token rotation. Reuse of a rotated token revokes the family. */
    @PostMapping("/refresh")
    public ResponseEntity<TokenPairResponse> refresh(
            @RequestBody RefreshRequest request) {
        if (request == null || request.refreshToken() == null
                || request.refreshToken().isBlank()) {
            return unauthorized();
        }
        RefreshTokenStore.RefreshResult result =
                refreshTokens.refresh(request.refreshToken());
        if (result instanceof RefreshTokenStore.RefreshResult.Accepted accepted) {
            // Account status is re-checked on EVERY refresh: a disabled,
            // deleted or not-yet-registered account must not keep minting
            // access tokens from a refresh token issued while it was active.
            if (!accounts.knowsUser(accepted.userId())
                    || !accounts.isRegistered(accepted.userId())) {
                refreshTokens.revokeAllForUser(accepted.userId());
                log.info("Refresh refused: account '{}' is no longer active — sessions revoked",
                        accepted.userId());
                return unauthorized();
            }
            return ResponseEntity.ok(new TokenPairResponse(
                    jwtService.issueAccessJwt(accepted.userId()),
                    jwtService.accessTtlSeconds(),
                    accepted.newRefreshToken()));
        }
        if (result instanceof RefreshTokenStore.RefreshResult.ReusedDetected) {
            log.warn("Refresh-token reuse on /api/auth/refresh — session family"
                    + " revoked, client must re-authenticate");
        }
        return unauthorized();
    }

    /** Logout: revokes the presented refresh-token session. Idempotent. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
        if (request != null && request.refreshToken() != null) {
            boolean revoked = refreshTokens.revoke(request.refreshToken());
            log.info("Logout: refresh session {}", revoked ? "revoked" : "already invalid");
        }
        return ResponseEntity.noContent().build();
    }

    /** Who am I? Validates the presented Bearer access token. */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(
            @RequestHeader(value = "Authorization", required = false) String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return unauthorizedMe();
        }
        try {
            JwtService.JwtAuthentication auth =
                    jwtService.validateAccessJwt(header.substring(7).trim());
            return ResponseEntity.ok(new MeResponse(auth.subject()));
        } catch (JwtService.JwtValidationException e) {
            return unauthorizedMe();
        }
    }

    /**
     * Resolves the authenticated workflow user from the request attribute
     * set by {@link JwtAuthFilter}. Controllers call this instead of
     * trusting any client-supplied identity.
     */
    public static String requireUser(HttpServletRequest request) {
        Object user = request.getAttribute(JwtAuthFilter.USER_ATTR);
        if (user == null) {
            throw new IllegalStateException("no authenticated user on request");
        }
        return user.toString();
    }

    private static ResponseEntity<TokenPairResponse> unauthorized() {
        return ResponseEntity.status(401)
                .body(new TokenPairResponse(null, 0, null));
    }

    private static ResponseEntity<MeResponse> unauthorizedMe() {
        return ResponseEntity.status(401).build();
    }

    /** Body of POST /api/auth/refresh and POST /api/auth/logout. */
    public record RefreshRequest(String refreshToken) {
    }
}

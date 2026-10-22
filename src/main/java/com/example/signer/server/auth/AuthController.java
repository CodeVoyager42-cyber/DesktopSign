package com.example.signer.server.auth;

import com.example.signer.server.api.ApiContracts.LoginRequest;
import com.example.signer.server.api.ApiContracts.MeResponse;
import com.example.signer.server.api.ApiContracts.TokenPairResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.security.AuthenticatedRequestFilter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Authentication endpoints (Phase 3/4): password login issues a short-lived
 * JWT access token plus a rotating refresh token; {@code /refresh} rotates
 * the refresh token (reuse is detected and kills the session family);
 * {@code /logout} revokes the presented refresh session and adds the access
 * token presented with it to the {@link AccessTokenDenyList} (Phase 2 risk
 * L4).
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
    private final AccessTokenDenyList deniedTokens;

    public AuthController(UserAccountService accounts,
                          JwtService jwtService,
                          RefreshTokenStore refreshTokens,
                          AccessTokenDenyList deniedTokens) {
        this.accounts = accounts;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.deniedTokens = deniedTokens;
    }

    /** Password login → access JWT + refresh token. */
    @PostMapping("/login")
    public ResponseEntity<TokenPairResponse> login(@RequestBody LoginRequest request) {
        if (request == null || request.username() == null
                || request.password() == null
                || !accounts.matches(request.username(), request.password())) {
            // Never the typed text: it is sometimes a password typed into the
            // wrong field, and it could inject fake log lines.
            log.info("Login failed for identifier {}",
                    LogSafe.fingerprint(request == null ? null : request.username()));
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

    /**
     * Logout: revokes the presented refresh-token session <b>and</b> denies
     * the access token presented with it. Idempotent.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest request,
                                       HttpServletRequest httpRequest) {
        if (request != null && request.refreshToken() != null) {
            boolean revoked = refreshTokens.revoke(request.refreshToken());
            log.info("Logout: refresh session {}", revoked ? "revoked" : "already invalid");
        }
        denyPresentedAccessToken(httpRequest);
        return ResponseEntity.noContent().build();
    }

    /**
     * Phase 2 risk L4: ends the access token too, instead of leaving it
     * working for the rest of its 15 minutes.
     *
     * <p>The bearer header is read <b>by hand</b> here, which looks odd in a
     * controller and is deliberate. {@code /api/auth/logout} is one of
     * {@code SecurityConfig.PUBLIC}, so the chain's token resolver returns
     * {@code null} for it on purpose: a stale or malformed access token must
     * never be the reason someone cannot sign out. Validating it here keeps
     * that property — anything the decoder refuses leaves the logout a 204
     * with nothing denied, because there is nothing a refused token could
     * still do.
     *
     * <p>No check that the access token and the refresh token belong to the
     * same account, and none is needed: denying a token requires presenting
     * it, and whoever holds a token can already use it. Ending it is the one
     * thing they can do with it that nobody needs protecting from.
     */
    private void denyPresentedAccessToken(HttpServletRequest httpRequest) {
        String bearer = bearerOf(httpRequest);
        if (bearer == null) {
            return;
        }
        JwtService.JwtAuthentication token;
        try {
            // validateAccessJwt, not the generic decode: a guest or task
            // token has no logout, and typ=access is what it enforces.
            token = jwtService.validateAccessJwt(bearer);
        } catch (JwtService.JwtValidationException e) {
            log.info("Logout: the access token presented with it is not usable ({}) —"
                    + " nothing to deny", e.reason());
            return;
        }
        deniedTokens.deny(token.tokenId(), token.expiresAt());
        log.info("Logout: access token of '{}' denied for the rest of its life",
                token.subject());
    }

    /** The {@code Authorization: Bearer} value, or {@code null}. */
    private static String bearerOf(HttpServletRequest request) {
        String header = request == null ? null : request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String value = header.substring(7).trim();
        return value.isEmpty() ? null : value;
    }

    /** Who am I? The account behind the presented access token (Spring Security checked it). */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(HttpServletRequest request) {
        return ResponseEntity.ok(new MeResponse(requireUser(request)));
    }

    /**
     * The authenticated user of this request, as established by Spring
     * Security and {@link AuthenticatedRequestFilter}. Controllers call this
     * instead of trusting any client-supplied identity.
     *
     * @throws ApiException {@code AUTHENTICATION_REQUIRED} (401) if the
     *         request carries no authenticated identity
     */
    public static String requireUser(HttpServletRequest request) {
        Object user = request == null ? null
                : request.getAttribute(AuthenticatedRequestFilter.USER_ATTR);
        if (user == null) {
            throw new ApiException(ApiErrorCode.AUTHENTICATION_REQUIRED,
                    "Authentication required");
        }
        return user.toString();
    }

    private static ResponseEntity<TokenPairResponse> unauthorized() {
        return ResponseEntity.status(401)
                .body(new TokenPairResponse(null, 0, null));
    }

    /** Body of POST /api/auth/refresh and POST /api/auth/logout. */
    public record RefreshRequest(String refreshToken) {
    }
}

package com.example.signer.server.invites;

import com.example.signer.server.api.ApiContracts.RegisterRequest;
import com.example.signer.server.api.ApiContracts.TokenPairResponse;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.auth.RefreshTokenStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/auth/register (brief §12/§16): invitation-bound registration.
 * Unrestricted sign-up is deliberately NOT offered — an email can only
 * register while holding a valid, unexpired, unaccepted invitation token;
 * the email itself comes from the invitation record, not the request body.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class RegistrationController {

    private final RegistrationService registration;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokens;

    public RegistrationController(RegistrationService registration,
                                  JwtService jwtService,
                                  RefreshTokenStore refreshTokens) {
        this.registration = registration;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
    }

    /** Registers through an invitation and authenticates the new account. */
    @PostMapping("/register")
    public ResponseEntity<TokenPairResponse> register(
            @RequestBody RegisterRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        String username = registration.register(request.invitationToken(),
                request.displayName(), request.password());
        String refresh = refreshTokens.issue(username);
        return ResponseEntity.ok(new TokenPairResponse(
                jwtService.issueAccessJwt(username),
                jwtService.accessTtlSeconds(),
                refresh));
    }
}

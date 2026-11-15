package com.example.signer.server.auth;

import com.example.signer.server.RemoteServerProperties;
import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.LoginRequest;
import com.example.signer.server.api.ApiContracts.TokenPairResponse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0.13: the account status is re-checked on every refresh, so an account
 * that is disabled or removed after login cannot keep minting access tokens
 * from its refresh token. Also: invitation mutations answer 401 (not 403)
 * for a missing/expired token so the client refreshes and retries.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RefreshAccountStatusTests {

    @Autowired
    private AuthController auth;

    @Autowired
    private RemoteServerProperties properties;

    @Autowired
    private JwtService jwtService;

    @Test
    void aRemovedAccountCannotRefreshItsSession() {
        ResponseEntity<TokenPairResponse> login =
                auth.login(new LoginRequest("charlie", "charlie-pass"),
                        new MockHttpServletRequest());
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String refresh = login.getBody().refreshToken();

        // Still active: refresh rotates normally.
        ResponseEntity<TokenPairResponse> rotated =
                auth.refresh(new AuthController.RefreshRequest(refresh));
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        String current = rotated.getBody().refreshToken();

        // The administrator removes the account.
        String hash = properties.getUsers().remove("charlie");
        try {
            ResponseEntity<TokenPairResponse> refused =
                    auth.refresh(new AuthController.RefreshRequest(current));
            assertThat(refused.getStatusCode().value()).isEqualTo(401);
        } finally {
            properties.getUsers().put("charlie", hash);
        }
        // The family was revoked: even with the account back, the old
        // refresh token stays dead (a fresh login is required).
        assertThat(auth.refresh(new AuthController.RefreshRequest(current))
                .getStatusCode().value()).isEqualTo(401);
    }
}

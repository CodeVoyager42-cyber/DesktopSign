package com.example.signer.server.auth;

import com.example.signer.server.RemoteServerProperties;
import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.LoginRequest;
import com.example.signer.server.api.ApiContracts.TokenPairResponse;
import com.example.signer.server.api.ApiErrorType;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0.13: the account status is re-checked on every refresh, so an account
 * that is disabled or removed after login cannot keep minting access tokens
 * from its refresh token. Also: a request without an
 * authenticated identity answers 401 (not 403), so the client refreshes its
 * token and retries.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RefreshAccountStatusTests {

    @Autowired
    private AuthController auth;

    @Autowired
    private RemoteServerProperties properties;

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

    /**
     * Spring Security answers 401 before the controllers run (see
     * SecurityHttpTests). As a second line, a request that reaches one
     * without an authenticated identity is still refused with 401 — never
     * 403, so the client refreshes its token and retries.
     */
    @Test
    void aRequestWithoutAnAuthenticatedIdentityAnswers401() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest();

        assertThatThrownBy(() -> auth.me(anonymous))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.code()).isEqualTo(ApiErrorCode.AUTHENTICATION_REQUIRED);
                    assertThat(new ApiErrorType.Handler().handle(api)
                            .getStatusCode().value()).isEqualTo(401);
                });
        assertThatThrownBy(() -> AuthController.requireUser(null))
                .isInstanceOf(ApiException.class);
    }
}

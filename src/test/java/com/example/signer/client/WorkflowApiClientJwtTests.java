package com.example.signer.client;

import com.example.signer.server.api.ApiContracts.WorkflowView;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client-side JWT verification (Phase 3/4): {@link WorkflowApiClient#login}
 * must authenticate against {@code /api/auth/login}, call the Bearer-protected
 * workflow API with the access token, refresh transparently, and revoke the
 * refresh session on close — the exact path the JavaFX dialog drives.
 */
@SpringBootTest(classes = com.example.signer.server.WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server")
class WorkflowApiClientJwtTests {

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> {
            try {
                return Files.createTempDirectory("client-jwt-store").toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @LocalServerPort
    private int port;

    @Test
    void loginBearerCallAndLogoutRevokeWorkEndToEnd() {
        String baseUrl = "http://localhost:" + port;
        char[] password = "alice-pass".toCharArray();

        WorkflowApiClient client = WorkflowApiClient.login(baseUrl, "alice", password);

        // PIN hygiene contract: login() must have wiped the password array.
        assertThat(password).containsOnly('\u0000');

        // Authenticated call through the Bearer session.
        List<WorkflowView> tasks = client.myTasks();
        assertThat(tasks).isNotNull();

        // The inviter's picker route (GET /api/workflows/owned) authenticates
        // the same way — empty on a fresh store, never an auth error.
        assertThat(client.myWorkflows()).isNotNull();

        // Wrong password is a clean, typed error — no tokens.
        assertThatThrownBy(() -> WorkflowApiClient.login(baseUrl, "alice",
                "wrong".toCharArray()))
                .isInstanceOf(com.example.signer.exception.SignerException.class)
                .hasMessageContaining("Wrong workflow user or password");

        // Close revokes the refresh session server-side and wipes secrets.
        client.close();
        assertThat(client.sessionForTest()).isNull();
    }

    @Autowired
    private com.example.signer.server.auth.RefreshTokenStore refreshTokens;

    /**
     * Regression: close() used to post the logout to a relative URL (the
     * session did not know its server), the request failed silently and the
     * refresh token stayed valid on the server.
     */
    @Test
    void closeRevokesTheRefreshTokenOnTheServer() {
        WorkflowApiClient client = WorkflowApiClient.login("http://localhost:" + port,
                "bob", "bob-pass".toCharArray());
        String refresh = client.sessionForTest().refreshToken();
        assertThat(refreshTokens.isUsable(refresh)).isTrue();

        client.close();

        assertThat(refreshTokens.isUsable(refresh)).isFalse();
    }
}

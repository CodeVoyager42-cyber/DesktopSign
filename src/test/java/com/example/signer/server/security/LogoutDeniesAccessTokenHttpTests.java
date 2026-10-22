package com.example.signer.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 2 risk L4 over HTTP: signing out ends the access token too,
 * not only the refresh session.
 *
 * <p>Before this, {@code /api/auth/logout} revoked a row and the signed JWT
 * the client was holding kept working for the rest of its 15 minutes. The
 * first test is the whole risk; the rest pin down the decisions around it —
 * that one device signing out does not sign out the others, that a token
 * nobody showed the server cannot be denied by it, and that a useless token
 * presented with a logout still cannot stop the logout.
 *
 * <p>File mode, so the list is {@code InMemoryAccessTokenDenyList}; the
 * database one is covered by {@link
 * com.example.signer.server.auth.DbAccessTokenDenyListLiveTests}. The
 * per-address sign-in limit is left wide open so that nothing here can be a
 * {@code 429} in disguise.
 */
@SpringBootTest(classes = com.example.signer.server.WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "signature.remote-server.login.rate-limit=100")
@ActiveProfiles("server")
class LogoutDeniesAccessTokenHttpTests {

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> {
            try {
                return Files.createTempDirectory("logout-deny-store").toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private String base;

    @LocalServerPort
    void capturePort(int port) {
        this.base = "http://localhost:" + port;
    }

    /* ------------------------------------------------------------------ */

    @Test
    void anAccessTokenIsRefusedAsSoonAsItsSessionIsSignedOutOf() throws Exception {
        Tokens session = login("alice", "alice-pass");

        // It works first, so the 401 below is about the logout and nothing else.
        assertThat(me(session.access())).isEqualTo(200);

        assertThat(logout(session)).isEqualTo(204);

        assertThat(me(session.access()))
                .as("the risk itself: the signed JWT had ~15 minutes left on it")
                .isEqualTo(401);
        // Not only /api/auth/me — the deny-list is checked in the filter that
        // every bearer request passes through.
        assertThat(tasks(session.access())).isEqualTo(401);
        assertThat(refresh(session.refresh()))
                .as("and the refresh session is revoked as it always was").isEqualTo(401);
    }

    @Test
    void oneDeviceSigningOutLeavesTheAccountsOtherSessionsAlone() throws Exception {
        // This is why a logout cannot reuse the mechanism behind risk D7: a
        // password change ends every token of the account on purpose, and
        // signing out of one device has to do the opposite.
        Tokens phone = login("alice", "alice-pass");
        Tokens desktop = login("alice", "alice-pass");

        assertThat(logout(phone)).isEqualTo(204);

        assertThat(me(phone.access())).isEqualTo(401);
        assertThat(me(desktop.access()))
                .as("the other session of the same account is untouched").isEqualTo(200);
        assertThat(refresh(desktop.refresh()))
                .as("and can still rotate its refresh token").isEqualTo(200);
    }

    @Test
    void signingOutTwiceIsStillA204AndTheTokenStaysDenied() throws Exception {
        Tokens session = login("bob", "bob-pass");

        assertThat(logout(session)).isEqualTo(204);
        assertThat(logout(session))
                .as("idempotent: one entry, not a unique-constraint violation")
                .isEqualTo(204);
        assertThat(me(session.access())).isEqualTo(401);
    }

    @Test
    void aLogoutThatPresentsNoAccessTokenCanOnlyRevokeTheRefreshSession()
            throws Exception {
        // Honest about the shape of the fix: the server cannot delete a
        // signed JWT, so it can only refuse one it has been told about. A
        // client that posts the refresh token alone — as every build before
        // this one did — gets the old behaviour, which is exactly why the
        // desktop client now sends its bearer with the logout.
        Tokens session = login("bob", "bob-pass");

        assertThat(logoutWithoutBearer(session.refresh())).isEqualTo(204);

        assertThat(refresh(session.refresh())).isEqualTo(401);
        assertThat(me(session.access()))
                .as("nothing was presented to deny, so nothing was denied").isEqualTo(200);
    }

    @Test
    void aUselessAccessTokenPresentedWithALogoutStillCannotBlockIt() throws Exception {
        // /api/auth/logout is public precisely so that a stale or malformed
        // token can never be the reason someone cannot sign out. Reading the
        // bearer by hand in the controller must not have changed that.
        Tokens session = login("charlie", "charlie-pass");

        assertThat(logoutWithBearer(session.refresh(), "garbage"))
                .as("not a 401, and not a 500").isEqualTo(204);
        assertThat(refresh(session.refresh()))
                .as("the refresh session was still revoked").isEqualTo(401);

        // Same for a token whose signature does not check out: it is refused
        // by the decoder, so there is nothing to deny and nothing to report.
        Tokens other = login("charlie", "charlie-pass");
        assertThat(logoutWithBearer(other.refresh(), tamperWithSignature(other.access())))
                .isEqualTo(204);
    }

    /* ------------------------------ helpers ----------------------------- */

    private record Tokens(String access, String refresh) {
    }

    /** Flips the last character of the JWT's signature segment. */
    private static String tamperWithSignature(String jwt) {
        char last = jwt.charAt(jwt.length() - 1);
        return jwt.substring(0, jwt.length() - 1) + (last == 'A' ? 'B' : 'A');
    }

    private Tokens login(String user, String password) throws Exception {
        HttpResponse<String> response = postJson("/api/auth/login",
                "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}", null);
        assertThat(response.statusCode()).as("sign-in for the fixture").isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        return new Tokens(body.path("accessToken").asText(),
                body.path("refreshToken").asText());
    }

    private int logout(Tokens session) throws Exception {
        return logoutWithBearer(session.refresh(), session.access());
    }

    private int logoutWithBearer(String refreshToken, String bearer) throws Exception {
        return postJson("/api/auth/logout",
                "{\"refreshToken\":\"" + refreshToken + "\"}", bearer).statusCode();
    }

    private int logoutWithoutBearer(String refreshToken) throws Exception {
        return postJson("/api/auth/logout",
                "{\"refreshToken\":\"" + refreshToken + "\"}", null).statusCode();
    }

    private int refresh(String refreshToken) throws Exception {
        return postJson("/api/auth/refresh",
                "{\"refreshToken\":\"" + refreshToken + "\"}", null).statusCode();
    }

    private int me(String accessToken) throws Exception {
        return get("/api/auth/me", accessToken).statusCode();
    }

    private int tasks(String accessToken) throws Exception {
        return get("/api/workflows/my-tasks", accessToken).statusCode();
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.base + path));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body, String bearer)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

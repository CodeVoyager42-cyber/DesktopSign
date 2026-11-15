package com.example.signer.server.launch;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.security.RateLimitTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scope B, step 1b: the four launch-code routes are gone, and say so.
 *
 * <p>410 and not 404, for one release. A desktop or website build from before
 * the removal still calls these; 410 with a {@code FEATURE_REMOVED} code is a
 * removal its own error handling can report, where a 404 reads as a wrong URL
 * and invites a retry, and the bearer chain's terminal {@code denyAll} would
 * answer 401 — "sign in again", advice that can never work.
 *
 * <p>These tests are deliberately short-lived: step 1c deletes the routes
 * outright, and this class is replaced by one asserting 404.
 *
 * <p>What is NOT asserted here, because it is still true in 1b: a task token
 * issued before the removal is still accepted by
 * {@code AuthenticatedRequestFilter}. No new one can be minted — both the
 * issue and the exchange route are gone — so that surface drains within the
 * session lifetime and is removed in 1c.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-gone",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}",
                "signature.remote-server.login.rate-limit=1000"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class LaunchRoutesGoneTests {

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    /** Mints the token the removed exchange route used to hand out. */
    @Autowired
    private JwtService jwtService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clearSharedLimits() {
        // The per-address budgets are shared and persistent, so an earlier
        // class could otherwise have spent this address's allowance.
        RateLimitTestSupport.clear(dataSource);
    }

    /**
     * The exchange route is the one that matters most: it is public (the code
     * was the credential), so an out-of-date desktop reaches it with no token
     * and must be told the path is gone rather than that it is unauthorised.
     */
    @Test
    void theExchangeRouteIsGoneAndNeedsNoTokenToSaySo() throws Exception {
        Reply r = call("POST", "/api/desktop/launch", null,
                "{\"code\":\"" + "a".repeat(43) + "\",\"appVersion\":\"1.2.0\"}");

        assertThat(r.status()).as(r.body()).isEqualTo(410);
        assertThat(r.code()).isEqualTo("FEATURE_REMOVED");
        assertThat(r.body())
                .as("the message points at what to do instead, not only at the loss")
                .contains("desktop application");
    }

    /** A malformed or absent body changes nothing: the route is gone either way. */
    @Test
    void theExchangeRouteIsGoneEvenWithoutABody() throws Exception {
        assertThat(call("POST", "/api/desktop/launch", null, null).status()).isEqualTo(410);
    }

    /**
     * The issue route and the session-listing routes sit behind the account
     * chain, so they answer 401 without a token — the chain refusing first is
     * correct and is asserted so the 410 below is known to be the handler's
     * answer and not an accident of ordering.
     */
    @Test
    void theAccountRoutesStillRefuseAnAnonymousCallerBeforeAnswering() throws Exception {
        assertThat(call("POST", "/api/workflows/wf-1/launch-codes", null, null).status())
                .isEqualTo(401);
        assertThat(call("GET", "/api/desktop/launches", null, null).status())
                .isEqualTo(401);
        assertThat(call("POST", "/api/desktop/launches/1/revoke", null, null).status())
                .isEqualTo(401);
    }

    /** With a real session, each of the three answers the removal. */
    @Test
    void theAccountRoutesAreGoneForASignedInCaller() throws Exception {
        String token = login("bob");

        for (String[] route : new String[][] {
                {"POST", "/api/workflows/wf-1/launch-codes"},
                {"GET", "/api/desktop/launches"},
                {"POST", "/api/desktop/launches/1/revoke"}}) {
            Reply r = call(route[0], route[1], token, null);
            assertThat(r.status()).as(route[1] + " -> " + r.body()).isEqualTo(410);
            assertThat(r.code()).as(route[1]).isEqualTo("FEATURE_REMOVED");
        }
    }

    /**
     * The website's own route goes the same way. It is on the session chain,
     * so an anonymous call is refused there first; what matters here is that
     * no launch code can be minted from a browser either.
     */
    @Test
    void theWebsiteRouteIsGoneToo() throws Exception {
        assertThat(call("POST", "/web/api/workflows/wf-1/launch-codes", null, null).status())
                .as("the website chain refuses an anonymous, CSRF-less POST before the handler")
                .isIn(401, 403);
    }

    /**
     * No route can mint a task token any more, and a task token invented
     * without one is refused.
     *
     * <p>This is the assertion that matters once the issue and exchange
     * routes are gone: {@code AuthenticatedRequestFilter}'s task branch is
     * still live in this step, so the question is whether anything can still
     * open a task session. Nothing can — the branch resolves a token's
     * {@code lc} claim against a <i>consumed, unrevoked</i> {@code
     * launch_codes} row belonging to the same user, slot and workflow
     * ({@code LaunchCodeService.taskSessionState}), and with no route left to
     * write such a row, a correctly signed task token has nothing behind it.
     *
     * <p>It also replaces what {@code PasswordChangeEndsTokensHttpTests} used
     * to cover here. That test opened a real task session to prove a password
     * change ended it; it could only do so through the routes this step
     * removed, so the session it needs can no longer exist. The generic rule
     * — a token issued before a password change stops working — is still
     * covered there for access tokens, which is where the rule lives.
     */
    @Test
    void noTaskSessionCanBeOpenedAnyMore() throws Exception {
        String orphan = jwtService.issueTaskJwt("bob", "wf-1", 1, 1L, Duration.ofMinutes(30));

        assertThat(call("GET", "/api/workflows/wf-1/task", orphan, null).status())
                .as("a signed task token with no launch row behind it is refused")
                .isEqualTo(401);
    }

    /** Removing these routes must not have widened anything next to them. */
    @Test
    void theSigningApiItselfIsUntouched() throws Exception {
        String token = login("bob");

        assertThat(call("GET", "/api/workflows/my-tasks", token, null).status())
                .as("the signing API a desktop signer actually uses still answers")
                .isEqualTo(200);
        assertThat(call("GET", "/api/desktop/launches", token, null).status())
                .as("while the removed neighbour does not")
                .isEqualTo(410);
    }

    /* ------------------------------ helpers ----------------------------- */

    private record Reply(int status, String body) {
        JsonNode json() {
            try {
                return new ObjectMapper().readTree(body);
            } catch (Exception e) {
                throw new AssertionError("not JSON: " + body, e);
            }
        }

        String code() {
            return json().path("code").asText(null);
        }
    }

    private Reply call(String method, String path, String bearer, String json) throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (json != null) {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body());
    }

    private String login(String user) throws Exception {
        Reply r = call("POST", "/api/auth/login", null,
                "{\"username\":\"" + user + "\",\"password\":\"" + user + "-pass\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json().path("accessToken").asText();
    }
}

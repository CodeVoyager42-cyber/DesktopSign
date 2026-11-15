package com.example.signer.server.security;

import com.example.signer.server.WorkflowServerApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The desktop launch-code path is gone, and cannot be reached by hand.
 *
 * <p>Step 1b answered these routes with 410 for one release so stale clients
 * would fail legibly. Step 1c deleted them, so they are now 404 — nothing
 * there, like any path that never existed. This class replaces
 * {@code LaunchRoutesGoneTests}, which asserted the 410 window.
 *
 * <p>The assertion that earns its place long-term is the last one: a
 * correctly signed token carrying {@code typ=task} — the type the removed
 * path minted — must be <b>refused</b>, not merely unauthorised for a
 * particular route. The server can still verify such a token's signature,
 * because the signing key did not change, so "we deleted the code that made
 * them" is not by itself evidence that one cannot be used. This proves the
 * converter admits exactly two types and treats everything else as invalid.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-removed",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}",
                "signature.remote-server.login.rate-limit=1000",
                "signature.remote-server.jwt-secret=launch-removal-test-secret-at-least-32-bytes-long"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class LaunchPathRemovedTests {

    /**
     * The context's signing secret, fixed so this test can sign a token the
     * server will accept the signature of. A compile-time constant because
     * the {@code @SpringBootTest} properties above cannot reference a field of
     * the class they annotate, so the literal is repeated there. A mismatch
     * cannot pass unnoticed: aTaskTypeTokenIsRefusedEverywhere asserts that a
     * hand-built typ=access token IS honoured, which fails at once if the two
     * secrets differ.
     */
    static final String SECRET = "launch-removal-test-secret-at-least-32-bytes-long";

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clearSharedLimits() {
        RateLimitTestSupport.clear(dataSource);
    }

    /**
     * The public exchange route answers 404, not 410 and not 401.
     *
     * <p>It was public, so this is the one route whose absence a stranger can
     * observe directly, and 404 is what it must say: there is no endpoint.
     */
    @Test
    void theExchangeRouteIsNotThere() throws Exception {
        HttpResponse<String> r = send("POST", "/api/desktop/launch", null,
                "{\"code\":\"" + "a".repeat(43) + "\",\"appVersion\":\"1.2.0\"}");

        assertThat(r.statusCode()).as(r.body()).isEqualTo(404);
        assertThat(r.body()).doesNotContain("FEATURE_REMOVED");
    }

    /**
     * The three account-chain routes are refused too.
     *
     * <p>Anonymous, these answer 401 rather than 404 — the bearer chain
     * refuses before routing, and that is the chain working as designed, not
     * a trace of the removed feature. What matters is that none of them is
     * reachable; the signed-in case below shows the handler really is gone.
     */
    @Test
    void theAccountRoutesAreNotThere() throws Exception {
        for (String[] route : new String[][] {
                {"POST", "/api/workflows/wf-1/launch-codes"},
                {"GET", "/api/desktop/launches"},
                {"POST", "/api/desktop/launches/1/revoke"}}) {
            HttpResponse<String> r = send(route[0], route[1], null, null);
            assertThat(r.statusCode()).as(route[1] + " anonymous").isIn(401, 404);
        }

        String token = login("bob");
        for (String[] route : new String[][] {
                {"POST", "/api/workflows/wf-1/launch-codes"},
                {"GET", "/api/desktop/launches"},
                {"POST", "/api/desktop/launches/1/revoke"}}) {
            HttpResponse<String> r = send(route[0], route[1], token, null);
            assertThat(r.statusCode()).as(route[1] + " signed in -> " + r.body())
                    .isEqualTo(404);
        }
    }

    /** The website's route is gone as well. */
    @Test
    void theWebsiteRouteIsNotThere() throws Exception {
        HttpResponse<String> r =
                send("POST", "/web/api/workflows/wf-1/launch-codes", null, null);

        assertThat(r.statusCode()).as(r.body()).isIn(401, 403, 404);
    }

    /**
     * A correctly signed {@code typ=task} token is refused outright.
     *
     * <p>Minted here with the server's own key through the same encoder the
     * live code uses, so this is not a forgery the signature check catches —
     * it is a token this server would once have honoured. It must now fail as
     * an invalid token, because {@code TokenAuthenticationConverter} accepts
     * {@code guest} and {@code access} and nothing else.
     */
    @Test
    void aTaskTypeTokenIsRefusedEverywhere() throws Exception {
        // First: a hand-built token of the SAME construction, differing only
        // in its typ, is accepted. That is what makes the refusals below
        // evidence about the type rather than about the token being broken.
        assertThat(send("GET", "/api/workflows/my-tasks", accessTokenByHand("bob"), null)
                .statusCode())
                .as("a hand-built typ=access token is honoured, so the construction is sound")
                .isEqualTo(200);

        String task = taskToken("bob", "wf-1", 1);

        for (String path : new String[] {
                "/api/workflows/wf-1/task",
                "/api/workflows/wf-1/document",
                "/api/workflows/my-tasks"}) {
            assertThat(send("GET", path, task, null).statusCode())
                    .as("typ=task must not open " + path)
                    .isEqualTo(401);
        }
    }

    /** And the signing API a real desktop signer uses is untouched. */
    @Test
    void theRemainingSigningPathStillWorks() throws Exception {
        String token = login("bob");

        assertThat(send("GET", "/api/workflows/my-tasks", token, null).statusCode())
                .as("an account token still reads its own task list")
                .isEqualTo(200);
        assertThat(send("GET", "/api/health", null, null).statusCode())
                .as("and the health probe, which keeps minDesktopVersion")
                .isEqualTo(200);
    }

    /* ------------------------------ helpers ----------------------------- */

    /**
     * A token shaped exactly like the ones the removed exchange route issued:
     * {@code typ=task} with the {@code wf}, {@code slot} and {@code lc}
     * claims, the real issuer and audience, a lifetime inside the server's
     * bound, and <b>signed with this server's own key</b>.
     *
     * <p>Minted here rather than through {@code JwtService} because the method
     * that used to mint it is deleted — which is exactly what makes the test
     * worth having. The signing key is unchanged by the removal, so a token
     * of this shape still verifies; nothing but the type check stands between
     * it and a signing session, and that is what is being asserted. Signing
     * it with the same {@code SECRET} the context is configured with is what
     * keeps this a real token rather than a forgery the signature check would
     * catch for an unrelated reason.
     */
    private String taskToken(String principal, String workflowId, int slot) {
        SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(principal)
                .issuer("signer-coordination-server")
                .audience(List.of("signer-desktop-clients"))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(10)))
                .claim("typ", "task")
                .claim("wf", workflowId)
                .claim("slot", slot)
                .claim("lc", 1L)
                .build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /**
     * Proof that {@link #taskToken} really is a token this server accepts the
     * signature of: the same builder with {@code typ=access} opens an account
     * route. Without this, the 401s above could be a malformed token rather
     * than a refused type, and the test would pass for the wrong reason.
     */
    private String accessTokenByHand(String principal) {
        SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(principal)
                .issuer("signer-coordination-server")
                .audience(List.of("signer-desktop-clients"))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(10)))
                .claim("typ", "access")
                .build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private HttpResponse<String> send(String method, String path, String bearer, String json)
            throws Exception {
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
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String user) throws Exception {
        HttpResponse<String> r = send("POST", "/api/auth/login", null,
                "{\"username\":\"" + user + "\",\"password\":\"" + user + "-pass\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readTree(r.body()).path("accessToken").asText();
    }
}

package com.example.signer.server.security;

import com.example.signer.server.RemoteServerProperties;
import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.auth.TestJwts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 2, over real HTTP: what Spring Security lets through and
 * what it refuses. File mode (no database), so it always runs.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "signature.remote-server.jwt-secret=" + SecurityHttpTests.SECRET)
@ActiveProfiles("server")
@ExtendWith(OutputCaptureExtension.class)
class SecurityHttpTests {

    /** 64 bytes, so an HS512 forgery with the same secret is possible to build. */
    static final String SECRET =
            "security-http-tests-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private static final String OTHER_SECRET =
            "a-completely-different-secret-0123456789-abcdefghijklmnopqrstuv";

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> {
            try {
                return Files.createTempDirectory("security-http").toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RemoteServerProperties properties;

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json, String authorization)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String loginAccessToken(String user, String password) throws Exception {
        HttpResponse<String> r = post("/api/auth/login",
                "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}", null);
        assertThat(r.statusCode()).isEqualTo(200);
        return mapper.readTree(r.body()).path("accessToken").asText();
    }

    private void assertUnauthorized(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                v -> assertThat(v).startsWith("Bearer"));
        JsonNode body = mapper.readTree(r.body());
        assertThat(body.path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        // Never says why: expired, forged and malformed all look the same.
        assertThat(body.path("message").asText()).isEqualTo("Authentication required");
    }

    /* ----------------------------- tokens ----------------------------- */

    @Test
    void protectedRoutesRefuseRequestsWithoutAToken() throws Exception {
        for (String path : new String[] {"/api/workflows/my-tasks", "/api/workflows/owned",
                "/api/workflows/WF-1", "/api/auth/me", "/api/invitations/abc/accept"}) {
            HttpResponse<String> r = path.endsWith("/accept")
                    ? post(path, "{}", null) : get(path, null);
            assertUnauthorized(r);
        }
    }

    @Test
    void everyKindOfBadTokenGetsTheSame401() throws Exception {
        Instant now = Instant.now();
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("empty", "Bearer ");
        bad.put("garbage", "Bearer not.a.jwt");
        bad.put("basic scheme", "Basic YWxpY2U6YWxpY2UtcGFzcw==");
        bad.put("wrong key", "Bearer " + TestJwts.sign(OTHER_SECRET, JWSAlgorithm.HS256,
                TestJwts.access("alice").build()));
        bad.put("HS512 not allowed", "Bearer " + TestJwts.sign(SECRET, JWSAlgorithm.HS512,
                TestJwts.access("alice").build()));
        bad.put("alg none", "Bearer " + TestJwts.unsigned(TestJwts.access("alice").build()));
        bad.put("expired", "Bearer " + TestJwts.sign(SECRET, JWSAlgorithm.HS256,
                TestJwts.access("alice").issueTime(Date.from(now.minusSeconds(120)))
                        .expirationTime(Date.from(now.minusSeconds(1))).build()));
        bad.put("wrong issuer", "Bearer " + TestJwts.sign(SECRET, JWSAlgorithm.HS256,
                TestJwts.access("alice").issuer("someone-else").build()));
        bad.put("wrong audience", "Bearer " + TestJwts.sign(SECRET, JWSAlgorithm.HS256,
                TestJwts.access("alice").audience("partner-backend").build()));
        bad.put("no audience", "Bearer " + TestJwts.sign(SECRET, JWSAlgorithm.HS256,
                TestJwts.access("alice").audience((String) null).build()));
        bad.put("wrong type", "Bearer " + TestJwts.sign(SECRET, JWSAlgorithm.HS256,
                TestJwts.access("alice").claim("typ", "refresh").build()));
        bad.put("no type", "Bearer " + TestJwts.sign(SECRET, JWSAlgorithm.HS256,
                TestJwts.access("alice").claim("typ", null).build()));
        bad.put("no subject", "Bearer " + TestJwts.sign(SECRET, JWSAlgorithm.HS256,
                TestJwts.access("alice").subject(null).build()));
        bad.put("unknown account", "Bearer " + TestJwts.sign(SECRET, JWSAlgorithm.HS256,
                TestJwts.access("mallory").build()));
        for (Map.Entry<String, String> e : bad.entrySet()) {
            HttpResponse<String> r = get("/api/workflows/my-tasks", e.getValue());
            assertThat(r.statusCode()).as(e.getKey()).isEqualTo(401);
            assertUnauthorized(r);
            assertUnauthorized(get("/api/auth/me", e.getValue()));
        }
    }

    @Test
    void aValidTokenIsAcceptedOnlyInTheAuthorizationHeader() throws Exception {
        String token = loginAccessToken("alice", "alice-pass");
        HttpResponse<String> me = get("/api/auth/me", "Bearer " + token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(me.body()).path("userId").asText()).isEqualTo("alice");
        assertThat(get("/api/workflows/my-tasks", "Bearer " + token).statusCode()).isEqualTo(200);

        // Same valid token in the URL: not accepted (URLs end up in logs).
        assertUnauthorized(get("/api/workflows/my-tasks?access_token=" + token, null));
    }

    @Test
    void theTokenTheServerIssuesUsesTheExpectedClaims() throws Exception {
        String token = jwtService.issueAccessJwt("alice");
        var jwt = jwtService.decoder().decode(token);
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("signer-coordination-server");
        assertThat(jwt.getAudience()).containsExactly("signer-desktop-clients");
        assertThat(jwt.getClaimAsString("typ")).isEqualTo("access");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        // Nothing sensitive in the payload.
        assertThat(jwt.getClaims().keySet())
                .containsExactlyInAnyOrder("sub", "iss", "aud", "jti", "typ", "iat", "exp");
    }

    /* ------------------------- public routes -------------------------- */

    @Test
    void aStaleTokenNeverBlocksSignInRefreshOrLogout() throws Exception {
        String junk = "Bearer not.a.jwt";
        HttpResponse<String> login = post("/api/auth/login",
                "{\"username\":\"bob\",\"password\":\"bob-pass\"}", junk);
        assertThat(login.statusCode()).isEqualTo(200);
        String refresh = mapper.readTree(login.body()).path("refreshToken").asText();
        HttpResponse<String> refreshed = post("/api/auth/refresh",
                "{\"refreshToken\":\"" + refresh + "\"}", junk);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        String next = mapper.readTree(refreshed.body()).path("refreshToken").asText();
        assertThat(post("/api/auth/logout", "{\"refreshToken\":\"" + next + "\"}", junk)
                .statusCode()).isEqualTo(204);
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameAnswer() throws Exception {
        HttpResponse<String> wrong = post("/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"nope-nope\"}", null);
        HttpResponse<String> unknown = post("/api/auth/login",
                "{\"username\":\"nobody-here\",\"password\":\"nope-nope\"}", null);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).isEqualTo(unknown.body());
    }

    @Test
    void unknownPathsAreClosedByDefault() throws Exception {
        assertUnauthorized(get("/api/not-a-route", null));
        assertUnauthorized(get("/admin", null));
        String token = loginAccessToken("alice", "alice-pass");
        assertThat(get("/admin", "Bearer " + token).statusCode()).isEqualTo(403);
    }

    @Test
    void noSessionCookieAndStandardSecurityHeaders() throws Exception {
        HttpResponse<String> login = post("/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"alice-pass\"}", null);
        assertThat(login.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(login.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(login.headers().firstValue("Cache-Control")).hasValueSatisfying(
                v -> assertThat(v).contains("no-store"));
    }

    /* ------------------------ account and guest ----------------------- */

    @Test
    void aRemovedAccountIsRefusedOnItsNextRequest() throws Exception {
        String token = loginAccessToken("charlie", "charlie-pass");
        assertThat(get("/api/auth/me", "Bearer " + token).statusCode()).isEqualTo(200);
        String hash = properties.getUsers().remove("charlie");
        try {
            assertUnauthorized(get("/api/auth/me", "Bearer " + token));
            assertUnauthorized(get("/api/workflows/my-tasks", "Bearer " + token));
        } finally {
            properties.getUsers().put("charlie", hash);
        }
    }

    @Test
    void aGuestTokenCannotActAsAnAccount() throws Exception {
        String guest = "Bearer " + jwtService.issueGuestJwt("slot-2", "WF-GUEST", 2, 7L,
                Instant.now().plusSeconds(600), Duration.ofMinutes(30));
        for (String path : new String[] {"/api/auth/me", "/api/workflows/my-tasks",
                "/api/workflows/WF-OTHER/task"}) {
            HttpResponse<String> r = get(path, guest);
            assertThat(r.statusCode()).as(path).isEqualTo(403);
        }
        assertThat(mapper.readTree(get("/api/workflows/WF-OTHER/task", guest).body())
                .path("code").asText()).isEqualTo("WORKFLOW_NOT_ACCESSIBLE");
        assertThat(post("/api/invitations/abc/accept", "{}", guest).statusCode()).isEqualTo(403);
    }

    @Test
    void anExpiredGuestTokenIsUnauthenticated() throws Exception {
        String token = TestJwts.sign(SECRET, JWSAlgorithm.HS256, new JWTClaimsSet.Builder()
                .subject("slot-2").issuer("signer-coordination-server")
                .audience("signer-desktop-clients").claim("typ", "guest")
                .claim("wf", "WF-GUEST").claim("slot", 2).claim("inv", 7)
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().minusSeconds(1))).build());
        assertUnauthorized(get("/api/workflows/WF-GUEST/task", "Bearer " + token));
    }

    /* ----------------------------- logging ---------------------------- */

    @Test
    void passwordsTokensAndTypedIdentifiersNeverReachTheLog(CapturedOutput output) throws Exception {
        String typedSecret = "Typed-Into-User-Field-7731";
        String wrongPassword = "Wrong-Password-Guess-5519";
        post("/api/auth/login", "{\"username\":\"" + typedSecret + "\",\"password\":\""
                + wrongPassword + "\"}", null);
        HttpResponse<String> ok = post("/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"alice-pass\"}", null);
        JsonNode tokens = mapper.readTree(ok.body());
        String access = tokens.path("accessToken").asText();
        String refresh = tokens.path("refreshToken").asText();
        get("/api/auth/me", "Bearer " + access);
        get("/api/workflows/my-tasks", "Bearer " + access + "x");   // rejected
        HttpResponse<String> rotated = post("/api/auth/refresh",
                "{\"refreshToken\":\"" + refresh + "\"}", null);
        post("/api/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}", null);   // reuse
        post("/api/auth/logout", "{\"refreshToken\":\""
                + mapper.readTree(rotated.body()).path("refreshToken").asText() + "\"}", null);

        String log = output.getAll();
        assertThat(log).contains("Login failed for identifier id#");
        assertThat(log).doesNotContain(typedSecret).doesNotContain(wrongPassword)
                .doesNotContain("alice-pass").doesNotContain(access).doesNotContain(refresh);
    }
}

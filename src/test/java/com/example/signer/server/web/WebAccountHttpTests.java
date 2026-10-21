package com.example.signer.server.web;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.auth.RefreshTokenStore;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.invites.ConsoleEmailService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase D over real HTTP against a real PostgreSQL: sign-up, e-mail
 * verification, password reset, password change — and the two things they are
 * supposed to guarantee, namely that none of them reveals who has an account
 * and that a new password really does end every existing session.
 *
 * <p>The e-mailed links are read from the development console mailbox
 * ({@link ConsoleEmailService#latestAccountLink}), which is the only place
 * they exist: the database holds nothing but their SHA-256 hashes.
 *
 * <p>Own throwaway database; skipped when PostgreSQL is not reachable.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.jwt-secret=" + WebAccountHttpTests.SECRET,
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-web-account-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + WebAccountHttpTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.login.rate-limit=1000",
                // Per-recipient mail limit, raised so the tests can send
                // several without tripping it; its own test lowers it.
                "signature.remote-server.web.mail-limit=50",
                "signature.remote-server.web.session.cookie-name=__Host-SF_SESSION",
                "signature.remote-server.web.session.secure-cookie=true",
                "signature.remote-server.web.session.anonymous-idle-timeout=PT5M",
                "signature.remote-server.web.session.idle-timeout=PT30M"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.web.WebAccountHttpTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
@ExtendWith(OutputCaptureExtension.class)
class WebAccountHttpTests {

    static final String DB = "signflow_web_account_test";
    static final String SECRET =
            "web-account-tests-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private static final String SESSION_COOKIE = "__Host-SF_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String STRONG = "a-strong-password-9";
    private static final String REPLACEMENT = "an-even-better-one-7";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private ConsoleEmailService mailbox;

    @Autowired
    private RefreshTokenStore refreshTokens;

    public static boolean freshDatabase() {
        String url = System.getenv().getOrDefault("SIGNER_DB_URL",
                "jdbc:postgresql://localhost:5432/postgres");
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + DB);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ----------------------------- plumbing ---------------------------- */

    private static String newAddress() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static Optional<String> cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .map(h -> h.substring(name.length() + 1).split(";", 2)[0])
                .filter(v -> !v.isEmpty())
                .findFirst();
    }

    private record Csrf(String token, String session) {
        String cookieHeader() {
            String csrf = CSRF_COOKIE + "=" + token;
            return session == null ? csrf : csrf + "; " + SESSION_COOKIE + "=" + session;
        }

        String withSession(String value) {
            return CSRF_COOKIE + "=" + token + "; " + SESSION_COOKIE + "=" + value;
        }
    }

    private Csrf csrf(String sessionValue) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/web/api/csrf")).GET();
        if (sessionValue != null) {
            b.header("Cookie", SESSION_COOKIE + "=" + sessionValue);
        }
        HttpResponse<String> response =
                http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(204);
        return new Csrf(cookie(response, CSRF_COOKIE).orElseThrow(),
                sessionValue != null ? sessionValue
                        : cookie(response, SESSION_COOKIE).orElse(null));
    }

    private HttpResponse<String> post(String path, String json, Csrf csrf) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (csrf != null) {
            b.header("Cookie", csrf.cookieHeader());
            b.header("X-XSRF-TOKEN", csrf.token());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postOn(String path, String json, Csrf csrf, String session)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Cookie", csrf.withSession(session))
                .header("X-XSRF-TOKEN", csrf.token())
                .POST(HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (session != null) {
            b.header("Cookie", SESSION_COOKIE + "=" + session);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String json(String... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(pairs[i]).append("\":\"").append(pairs[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    /** Registers and verifies an address, returning its username. */
    private String registeredAndVerified(String address) throws Exception {
        assertThat(post("/web/api/auth/register",
                json("email", address, "password", STRONG, "displayName", "Test"),
                csrf(null)).statusCode()).isEqualTo(202);
        assertThat(post("/web/api/auth/verify-email",
                json("token", tokenFromMailbox(address)), csrf(null)).statusCode())
                .isEqualTo(204);
        return users.findByEmail(address).orElseThrow().getUsername();
    }

    /** Signs in and returns the session cookie value. */
    private String signIn(String address, String password) throws Exception {
        HttpResponse<String> response = post("/web/api/auth/login",
                json("username", address, "password", password), csrf(null));
        assertThat(response.statusCode()).as("sign-in should succeed").isEqualTo(200);
        return cookie(response, SESSION_COOKIE).orElseThrow();
    }

    /** The raw token out of the link in the development mailbox. */
    private String tokenFromMailbox(String address) {
        String link = mailbox.latestAccountLink(address);
        assertThat(link).as("an e-mail should have been sent to " + address).isNotNull();
        String query = link.substring(link.indexOf("token=") + "token=".length());
        return URLDecoder.decode(query, StandardCharsets.UTF_8);
    }

    private static long count(String sql) throws Exception {
        String url = "jdbc:postgresql://localhost:5432/" + DB;
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * One statement against the test database. Used to move a token's
     * timestamps into the past, which is how the expiry tests below age a
     * link without sleeping for a day.
     */
    private static void exec(String sql) throws Exception {
        String url = "jdbc:postgresql://localhost:5432/" + DB;
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    /* ------------------------------ sign-up ---------------------------- */

    @Test
    void signUpCreatesAnUnverifiedAccountAndMailsALink() throws Exception {
        String address = newAddress();

        assertThat(post("/web/api/auth/register",
                json("email", address, "password", STRONG, "displayName", "Ada"),
                csrf(null)).statusCode()).isEqualTo(202);

        UserEntity account = users.findByEmail(address).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getEmailVerifiedAt()).as("not verified until the link is used")
                .isNull();
        assertThat(mailbox.latestAccountLink(address)).contains("/verify-email?token=");
        // Only the hash is stored.
        String raw = tokenFromMailbox(address);
        assertThat(count("SELECT count(*) FROM email_verification_tokens"
                + " WHERE token_hash = '" + raw + "'")).isZero();
        assertThat(count("SELECT count(*) FROM email_verification_tokens"
                + " WHERE username = '" + account.getUsername() + "'")).isEqualTo(1);
    }

    @Test
    void signUpWithATakenAddressIsIndistinguishableAndChangesNothing() throws Exception {
        String address = newAddress();
        String username = registeredAndVerified(address);
        String hashBefore = users.findByUsername(username).orElseThrow().getPasswordHash();

        HttpResponse<String> again = post("/web/api/auth/register",
                json("email", address, "password", "a-different-password-1",
                        "displayName", "Impostor"), csrf(null));

        assertThat(again.statusCode()).as("same answer as a free address").isEqualTo(202);
        assertThat(again.body()).isEmpty();
        UserEntity account = users.findByUsername(username).orElseThrow();
        assertThat(account.getPasswordHash())
                .as("a stranger must not be able to touch an existing account")
                .isEqualTo(hashBefore);
        assertThat(account.getDisplayName()).isNotEqualTo("Impostor");
        assertThat(users.findByEmail(address)).hasValueSatisfying(u ->
                assertThat(u.getUsername()).isEqualTo(username));
    }

    @Test
    void signUpRejectsAWeakPasswordBecauseThatIsAboutTheRequestNotTheAccount()
            throws Exception {
        HttpResponse<String> response = post("/web/api/auth/register",
                json("email", newAddress(), "password", "short", "displayName", "X"),
                csrf(null));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("8");
    }

    /* --------------------------- verification -------------------------- */

    @Test
    void theLinkVerifiesOnceAndThenNeverAgain() throws Exception {
        String address = newAddress();
        post("/web/api/auth/register",
                json("email", address, "password", STRONG, "displayName", "Ada"), csrf(null));
        String token = tokenFromMailbox(address);

        assertThat(post("/web/api/auth/verify-email", json("token", token), csrf(null))
                .statusCode()).isEqualTo(204);
        assertThat(users.findByEmail(address).orElseThrow().getEmailVerifiedAt()).isNotNull();

        HttpResponse<String> replay =
                post("/web/api/auth/verify-email", json("token", token), csrf(null));
        assertThat(replay.statusCode()).as("single use").isEqualTo(400);
        assertThat(replay.body()).contains("LINK_INVALID");
    }

    @Test
    void anUnknownOrMalformedTokenLooksExactlyLikeAUsedOne() throws Exception {
        HttpResponse<String> unknown = post("/web/api/auth/verify-email",
                json("token", "Zc3Kd9QwErTyUiOpAsDfGhJkZxCvBnM1234567890ab"), csrf(null));
        HttpResponse<String> malformed = post("/web/api/auth/verify-email",
                json("token", "nonsense"), csrf(null));

        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.body()).isEqualTo(unknown.body());
    }

    @Test
    void resendAnswersTheSameForAKnownAndAnUnknownAddress() throws Exception {
        String known = newAddress();
        post("/web/api/auth/register",
                json("email", known, "password", STRONG, "displayName", "Ada"), csrf(null));
        String unknown = newAddress();

        HttpResponse<String> forKnown =
                post("/web/api/auth/resend-verification", json("email", known), csrf(null));
        HttpResponse<String> forUnknown =
                post("/web/api/auth/resend-verification", json("email", unknown), csrf(null));

        assertThat(forKnown.statusCode()).isEqualTo(202);
        assertThat(forUnknown.statusCode()).isEqualTo(202);
        assertThat(forKnown.body()).isEqualTo(forUnknown.body());
        assertThat(mailbox.latestAccountLink(unknown))
                .as("nothing is sent to an address with no account").isNull();
    }

    @Test
    void aVerificationLinkOlderThan24HoursIsRefusedLikeAnUnknownOne() throws Exception {
        String address = newAddress();
        post("/web/api/auth/register",
                json("email", address, "password", STRONG, "displayName", "Ada"), csrf(null));
        String token = tokenFromMailbox(address);
        String username = users.findByEmail(address).orElseThrow().getUsername();

        // Age the link past its 24 h life rather than waiting for it. Both
        // timestamps move together, because V10 CHECKs expires_at > created_at.
        exec("UPDATE email_verification_tokens"
                + " SET created_at = created_at - INTERVAL '25 hours',"
                + "     expires_at = expires_at - INTERVAL '25 hours'"
                + " WHERE username = '" + username + "'");

        HttpResponse<String> expired =
                post("/web/api/auth/verify-email", json("token", token), csrf(null));
        HttpResponse<String> unknown = post("/web/api/auth/verify-email",
                json("token", "Zc3Kd9QwErTyUiOpAsDfGhJkZxCvBnM1234567890ab"), csrf(null));

        assertThat(expired.statusCode()).isEqualTo(400);
        assertThat(expired.statusCode()).isEqualTo(unknown.statusCode());
        assertThat(expired.body())
                .as("an expired link must not be distinguishable from an unknown one")
                .isEqualTo(unknown.body());
        assertThat(users.findByEmail(address).orElseThrow().getEmailVerifiedAt())
                .as("an expired link verifies nothing").isNull();
    }

    /* ------------------------- the unverified gate --------------------- */

    @Test
    void anUnverifiedAccountMaySignInButNotStartASigningRequest() throws Exception {
        String address = newAddress();
        post("/web/api/auth/register",
                json("email", address, "password", STRONG, "displayName", "Ada"), csrf(null));
        String username = users.findByEmail(address).orElseThrow().getUsername();

        // Signing in works: the decision is that they may look around.
        String session = signIn(address, STRONG);
        JsonNode me = mapper.readTree(get("/web/api/auth/me", session).body());
        assertThat(me.get("emailVerified").asBoolean()).isFalse();

        // ...but the desktop API refuses a signing request from that account.
        assertThat(createWorkflowStatus(username)).as("unverified cannot create a workflow")
                .isEqualTo(403);

        // After verifying, the same call is allowed through the gate.
        post("/web/api/auth/verify-email", json("token", tokenFromMailbox(address)),
                csrf(null));
        assertThat(createWorkflowStatus(username))
                .as("a verified account is past the gate").isNotEqualTo(403);
    }

    /**
     * Calls the desktop create-workflow endpoint as {@code username} with a
     * deliberately empty document, so the only thing that can produce 403 is
     * the verification gate (a bad document is 400).
     */
    private int createWorkflowStatus(String username) throws Exception {
        String bearer = bearerFor(username);
        String boundary = "b" + UUID.randomUUID();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"request\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + "{\"documentName\":\"x.pdf\",\"signers\":[{\"order\":1,\"userId\":\""
                + username + "\",\"certificateSha256\":null}]}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"x.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n"
                + "not-a-pdf\r\n"
                + "--" + boundary + "--\r\n";
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/workflows"))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    @Autowired
    private com.example.signer.server.auth.JwtService jwtService;

    private String bearerFor(String username) {
        return jwtService.issueAccessJwt(username);
    }

    /* ---------------------------- reset flow --------------------------- */

    @Test
    void resetRequestAnswersTheSameForAKnownAndAnUnknownAddress() throws Exception {
        String known = newAddress();
        registeredAndVerified(known);
        String unknown = newAddress();

        HttpResponse<String> forKnown = post("/web/api/auth/password-reset/request",
                json("email", known), csrf(null));
        HttpResponse<String> forUnknown = post("/web/api/auth/password-reset/request",
                json("email", unknown), csrf(null));

        assertThat(forKnown.statusCode()).isEqualTo(202);
        assertThat(forUnknown.statusCode()).isEqualTo(202);
        assertThat(forKnown.body()).isEqualTo(forUnknown.body());
        assertThat(mailbox.latestAccountLink(known)).contains("/reset-password?token=");
        assertThat(mailbox.latestAccountLink(unknown)).isNull();
    }

    @Test
    void aResetSetsTheNewPasswordAndEndsEverySessionAndRefreshToken() throws Exception {
        String address = newAddress();
        String username = registeredAndVerified(address);
        String session = signIn(address, STRONG);
        String refresh = refreshTokens.issue(username);
        assertThat(get("/web/api/auth/me", session).statusCode()).isEqualTo(200);

        post("/web/api/auth/password-reset/request", json("email", address), csrf(null));
        String token = tokenFromMailbox(address);
        assertThat(post("/web/api/auth/password-reset/confirm",
                json("token", token, "newPassword", REPLACEMENT), csrf(null)).statusCode())
                .isEqualTo(204);

        // Old password gone, new password works.
        assertThat(post("/web/api/auth/login", json("username", address, "password", STRONG),
                csrf(null)).statusCode()).isEqualTo(401);
        assertThat(signIn(address, REPLACEMENT)).isNotBlank();
        // C8: both session kinds are revoked.
        assertThat(get("/web/api/auth/me", session).statusCode())
                .as("every website session is ended").isEqualTo(401);
        assertThat(refreshTokens.isUsable(refresh))
                .as("desktop refresh tokens are ended too").isFalse();
        // And the link cannot be replayed.
        assertThat(post("/web/api/auth/password-reset/confirm",
                json("token", token, "newPassword", "yet-another-one-3"), csrf(null))
                .statusCode()).isEqualTo(400);
    }

    @Test
    void aResetLinkOlderThan30MinutesIsRefusedLikeAnUnknownOne() throws Exception {
        String address = newAddress();
        String username = registeredAndVerified(address);
        post("/web/api/auth/password-reset/request", json("email", address), csrf(null));
        String token = tokenFromMailbox(address);

        // 31 minutes past issue, moved in the database for the same reason.
        exec("UPDATE password_reset_tokens"
                + " SET created_at = created_at - INTERVAL '31 minutes',"
                + "     expires_at = expires_at - INTERVAL '31 minutes'"
                + " WHERE username = '" + username + "'");

        HttpResponse<String> expired = post("/web/api/auth/password-reset/confirm",
                json("token", token, "newPassword", REPLACEMENT), csrf(null));
        HttpResponse<String> unknown = post("/web/api/auth/password-reset/confirm",
                json("token", "Zc3Kd9QwErTyUiOpAsDfGhJkZxCvBnM1234567890ab",
                        "newPassword", REPLACEMENT), csrf(null));

        assertThat(expired.statusCode()).isEqualTo(400);
        assertThat(expired.statusCode()).isEqualTo(unknown.statusCode());
        assertThat(expired.body())
                .as("an expired link must not be distinguishable from an unknown one")
                .isEqualTo(unknown.body());
        // Nothing was set, so the original password still signs in and the
        // one the expired link carried does not.
        assertThat(signIn(address, STRONG)).isNotBlank();
        assertThat(post("/web/api/auth/login",
                json("username", address, "password", REPLACEMENT), csrf(null)).statusCode())
                .as("the expired link set no password").isEqualTo(401);
    }

    /* --------------------------- change password ------------------------ */

    @Test
    void changingThePasswordKeepsThisSessionAndEndsTheOthers() throws Exception {
        String address = newAddress();
        String username = registeredAndVerified(address);
        String mine = signIn(address, STRONG);
        String elsewhere = signIn(address, STRONG);
        String refresh = refreshTokens.issue(username);
        assertThat(get("/web/api/auth/me", elsewhere).statusCode()).isEqualTo(200);

        Csrf csrf = csrf(mine);
        assertThat(postOn("/web/api/me/password",
                json("currentPassword", STRONG, "newPassword", REPLACEMENT), csrf, mine)
                .statusCode()).isEqualTo(204);

        assertThat(get("/web/api/auth/me", mine).statusCode())
                .as("the browser doing the change stays signed in").isEqualTo(200);
        assertThat(get("/web/api/auth/me", elsewhere).statusCode())
                .as("every other session is ended").isEqualTo(401);
        assertThat(refreshTokens.isUsable(refresh)).isFalse();
        assertThat(post("/web/api/auth/login", json("username", address, "password", STRONG),
                csrf(null)).statusCode()).isEqualTo(401);
    }

    @Test
    void changingThePasswordNeedsTheCurrentOne() throws Exception {
        String address = newAddress();
        registeredAndVerified(address);
        String mine = signIn(address, STRONG);
        Csrf csrf = csrf(mine);

        HttpResponse<String> wrong = postOn("/web/api/me/password",
                json("currentPassword", "not-the-password", "newPassword", REPLACEMENT),
                csrf, mine);

        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(get("/web/api/auth/me", mine).statusCode())
                .as("a failed change does not sign anyone out").isEqualTo(200);
        assertThat(signIn(address, STRONG)).isNotBlank();
    }

    @Test
    void changingThePasswordNeedsASession() throws Exception {
        assertThat(post("/web/api/me/password",
                json("currentPassword", STRONG, "newPassword", REPLACEMENT), csrf(null))
                .statusCode()).isEqualTo(401);
    }

    /* ------------------------- C9 and the log -------------------------- */

    @Test
    void anAnonymousSessionGetsTheShortIdleTimeoutAndSignInPromotesIt() throws Exception {
        Csrf anonymous = csrf(null);

        assertThat(anonymous.session()).as("the CSRF bootstrap creates a session").isNotNull();
        assertThat(count("SELECT max_inactive_interval FROM spring_session"
                + " WHERE session_id = '" + anonymous.session() + "'"))
                .as("5 minutes while nobody has signed in").isEqualTo(300);

        String address = newAddress();
        registeredAndVerified(address);
        String session = signIn(address, STRONG);

        assertThat(count("SELECT max_inactive_interval FROM spring_session"
                + " WHERE session_id = '" + session + "'"))
                .as("30 minutes once signed in").isEqualTo(1800);
    }

    @Test
    void noTokenLinkOrPasswordReachesTheLog(CapturedOutput output) throws Exception {
        String address = newAddress();
        post("/web/api/auth/register",
                json("email", address, "password", STRONG, "displayName", "Ada"), csrf(null));
        String verification = tokenFromMailbox(address);
        post("/web/api/auth/verify-email", json("token", verification), csrf(null));
        post("/web/api/auth/password-reset/request", json("email", address), csrf(null));
        String reset = tokenFromMailbox(address);
        post("/web/api/auth/password-reset/confirm",
                json("token", reset, "newPassword", REPLACEMENT), csrf(null));

        String logged = output.getAll();
        // Passwords are never printed anywhere at all.
        assertThat(logged).doesNotContain(STRONG);
        assertThat(logged).doesNotContain(REPLACEMENT);
        // The tokens DO appear in the captured output, because the console
        // mailbox prints the e-mail body to stdout and that is captured here
        // too. That is the development mailbox standing in for an inbox — it
        // is refused outside the dev profile. What must never carry a token is
        // a LOG line, which is what a real deployment keeps.
        List<String> logLines = logged.lines()
                .filter(line -> line.contains(" INFO ") || line.contains(" WARN ")
                        || line.contains(" ERROR "))
                .toList();
        assertThat(logLines)
                .as("no log line may carry a one-time token")
                .noneMatch(line -> line.contains(verification) || line.contains(reset));
        assertThat(logged).contains("Verification link sent for account");
        // Sanity: the mailbox really did print them, so the check above is
        // testing something rather than passing because nothing was sent.
        assertThat(logged).contains(verification);
    }

    /* ------------------------- the signing gate (D4) -------------------- */

    /**
     * Risk D4: the verified-address requirement covers the signing actions,
     * not just starting a workflow. The workflow id is deliberately one that
     * does not exist, so the gate is the only thing that can answer 403 — a
     * caller past it gets the uniform 404 for a workflow it cannot see.
     */
    @Test
    void anUnverifiedAccountIsRefusedEverySigningAction() throws Exception {
        String address = newAddress();
        post("/web/api/auth/register",
                json("email", address, "password", STRONG, "displayName", "Ada"), csrf(null));
        String username = users.findByEmail(address).orElseThrow().getUsername();
        String nowhere = "WF-does-not-exist";

        for (String action : List.of("/claim", "/signatures", "/launch-codes",
                "/signers/" + username + "/certificate")) {
            HttpResponse<String> response =
                    postAs("/api/workflows/" + nowhere + action, username);
            assertThat(response.statusCode()).as("POST " + action + " unverified")
                    .isEqualTo(403);
            assertThat(response.body()).as("POST " + action + " unverified")
                    .contains("EMAIL_NOT_VERIFIED");
        }

        // The download is gated too, even though it is a GET: it is step one
        // of signing rather than a way of looking around.
        HttpResponse<String> download =
                getAs("/api/workflows/" + nowhere + "/document", username);
        assertThat(download.statusCode()).as("GET /document unverified").isEqualTo(403);
        assertThat(download.body()).contains("EMAIL_NOT_VERIFIED");

        // Listing is deliberately still allowed: the account may see where it
        // stands.
        assertThat(getAs("/api/workflows/my-tasks", username).statusCode())
                .as("an unverified account may still list its workflows").isEqualTo(200);

        // Once verified, none of the four is stopped by the gate any more.
        post("/web/api/auth/verify-email", json("token", tokenFromMailbox(address)),
                csrf(null));
        for (String action : List.of("/claim", "/signatures", "/launch-codes",
                "/signers/" + username + "/certificate")) {
            assertThat(postAs("/api/workflows/" + nowhere + action, username).statusCode())
                    .as("POST " + action + " verified").isNotEqualTo(403);
        }
    }

    /** A GET of the desktop API as {@code username}. */
    private HttpResponse<String> getAs(String path, String username) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                        .header("Authorization", "Bearer " + bearerFor(username))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** An empty POST to the desktop API as {@code username}. */
    private HttpResponse<String> postAs(String path, String username) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                        .header("Authorization", "Bearer " + bearerFor(username))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

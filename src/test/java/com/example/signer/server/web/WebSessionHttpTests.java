package com.example.signer.server.web;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase C over real HTTP against a real PostgreSQL: the website's
 * session login, its CSRF requirement, and the strict separation from the
 * desktop's bearer domain.
 *
 * <p>Cookies are handled BY HAND rather than with a {@code CookieManager}, so
 * the assertions see exactly what the server sent — including {@code Secure}
 * on a plain-HTTP test connection, which a browser would refuse to store but
 * which is what production must emit.
 *
 * <p>The per-address rate limit is raised out of the way here; that it is
 * genuinely shared with {@code /api/auth/login} is
 * {@link WebLoginRateLimitSharedTests}.
 *
 * <p>Uses its own throwaway database, recreated for each run. Skipped when
 * PostgreSQL is not reachable.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.jwt-secret=" + WebSessionHttpTests.SECRET,
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-web-session-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + WebSessionHttpTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.login.max-failures=5",
                "signature.remote-server.login.lock-minutes=15",
                // Out of the way: this class is not about the rate limit.
                "signature.remote-server.login.rate-limit=1000",
                // The test classpath activates the dev profile, which weakens
                // the cookie so the site works over plain http://localhost.
                // These put the PRODUCTION cookie back, because that is what
                // the assertions below are about. Cookies are handled by hand
                // here, so a Secure cookie over a plain-HTTP test connection
                // is fine — no browser policy is in the way.
                "signature.remote-server.web.session.cookie-name=__Host-SF_SESSION",
                "signature.remote-server.web.session.secure-cookie=true"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.web.WebSessionHttpTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
@ExtendWith(OutputCaptureExtension.class)
class WebSessionHttpTests {

    static final String DB = "signflow_web_session_test";
    static final String SECRET =
            "web-session-tests-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private static final String PASSWORD = "correct-password-1";
    private static final String SESSION_COOKIE = "__Host-SF_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private final HttpClient http = HttpClient.newBuilder().build();
    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwtService;

    /** Recreates the throwaway database; false (skipped) when PostgreSQL is unreachable. */
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

    /* ----------------------------- helpers ----------------------------- */

    private String newAccount() {
        String name = "u" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new UserEntity(name, name + "@example.test",
                PasswordHashing.hash(PASSWORD), name, AccountStatus.ACTIVE));
        return name;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** The value of one cookie out of every {@code Set-Cookie} on a response. */
    private static Optional<String> cookie(HttpResponse<?> response, String name) {
        return setCookies(response).stream()
                .filter(h -> h.startsWith(name + "="))
                .map(h -> h.substring(name.length() + 1).split(";", 2)[0])
                .filter(v -> !v.isEmpty())
                .findFirst();
    }

    private static List<String> setCookies(HttpResponse<?> response) {
        return response.headers().allValues("set-cookie");
    }

    /** The whole {@code Set-Cookie} line for a cookie, attributes included. */
    private static String setCookieLine(HttpResponse<?> response, String name) {
        return setCookies(response).stream()
                .filter(h -> h.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Set-Cookie for " + name));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookieHeader, String bearer)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (cookieHeader != null) {
            b.header("Cookie", cookieHeader);
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return send(b.build());
    }

    private HttpResponse<String> post(String path, String json, String cookieHeader,
                                      String csrfToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json));
        if (cookieHeader != null) {
            b.header("Cookie", cookieHeader);
        }
        if (csrfToken != null) {
            b.header("X-XSRF-TOKEN", csrfToken);
        }
        return send(b.build());
    }

    /**
     * A fresh CSRF token and the cookies that go with it. The token is held
     * in the session, so the bootstrap's session cookie has to travel with it
     * from here on — exactly as a browser would carry it.
     */
    private Csrf bootstrapCsrf() throws Exception {
        HttpResponse<String> response = get("/web/api/csrf", null, null);
        assertThat(response.statusCode()).isEqualTo(204);
        String token = cookie(response, CSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie"));
        return new Csrf(token, cookie(response, SESSION_COOKIE).orElse(null));
    }

    private record Csrf(String token, String session) {

        /** Both cookies, as the bootstrap left them. */
        String cookieHeader() {
            String csrf = CSRF_COOKIE + "=" + token;
            return session == null ? csrf : csrf + "; " + SESSION_COOKIE + "=" + session;
        }

        /** This CSRF token, but carried on a different session. */
        String withSession(String sessionValue) {
            return CSRF_COOKIE + "=" + token + "; " + SESSION_COOKIE + "=" + sessionValue;
        }
    }

    /** The CSRF token an EXISTING session is holding. */
    private Csrf csrfFor(String sessionValue) throws Exception {
        HttpResponse<String> response =
                get("/web/api/csrf", SESSION_COOKIE + "=" + sessionValue, null);
        assertThat(response.statusCode()).isEqualTo(204);
        // Already-held tokens are not re-published, so fall back to the one
        // this session was given earlier in the response that created it.
        String token = cookie(response, CSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("no XSRF-TOKEN for that session"));
        return new Csrf(token, sessionValue);
    }

    /** Signs in and returns the session cookie value. */
    private String login(String username) throws Exception {
        Csrf csrf = bootstrapCsrf();
        HttpResponse<String> response = post("/web/api/auth/login",
                body(username, PASSWORD), csrf.cookieHeader(), csrf.token());
        assertThat(response.statusCode()).as("sign-in should succeed").isEqualTo(200);
        return cookie(response, SESSION_COOKIE)
                .orElseThrow(() -> new AssertionError("no session cookie"));
    }

    private static String body(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private static long sessionRows() throws Exception {
        return count("SELECT count(*) FROM spring_session");
    }

    /**
     * Whether THIS session still has a row. Asserted instead of a global
     * count, which other sessions in the same class would perturb — the CSRF
     * bootstrap creates one of its own before every sign-in.
     */
    private static boolean sessionRowExists(String sessionId) throws Exception {
        return count("SELECT count(*) FROM spring_session WHERE session_id = '"
                + sessionId.replace("'", "''") + "'") > 0;
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

    /* ------------------------- cookie and login ------------------------ */

    @Test
    void csrfBootstrapReturns204AndAReadableToken() throws Exception {
        HttpResponse<String> response = get("/web/api/csrf", null, null);

        assertThat(response.statusCode()).isEqualTo(204);
        String line = setCookieLine(response, CSRF_COOKIE);
        // The website must be able to read this one, so NOT HttpOnly.
        assertThat(line).doesNotContain("HttpOnly");
        assertThat(cookie(response, CSRF_COOKIE)).isPresent();
    }

    @Test
    void loginSetsAHardenedSessionCookieAndStoresTheSessionServerSide() throws Exception {
        String user = newAccount();
        long before = sessionRows();

        Csrf csrf = bootstrapCsrf();
        HttpResponse<String> response = post("/web/api/auth/login",
                body(user, PASSWORD), csrf.cookieHeader(), csrf.token());

        assertThat(response.statusCode()).isEqualTo(200);
        String line = setCookieLine(response, SESSION_COOKIE);
        assertThat(line).contains("HttpOnly");
        assertThat(line).contains("Secure");
        assertThat(line).contains("SameSite=Lax");
        assertThat(line).contains("Path=/");
        // __Host- forbids a Domain attribute.
        assertThat(line).doesNotContain("Domain");
        // The session lives in PostgreSQL, not only in this process.
        assertThat(sessionRows()).isGreaterThan(before);

        JsonNode json = mapper.readTree(response.body());
        assertThat(json.get("username").asText()).isEqualTo(user);
        assertThat(json.get("email").asText()).isEqualTo(user + "@example.test");
        assertThat(json.has("emailVerified")).isTrue();
        // Nothing token-shaped is handed to the browser.
        assertThat(response.body()).doesNotContain("accessToken", "refreshToken", "password");
    }

    @Test
    void loginRotatesTheSessionIdAndTheOldOneStopsWorking() throws Exception {
        String user = newAccount();
        String first = login(user);
        assertThat(get("/web/api/auth/me", SESSION_COOKIE + "=" + first, null).statusCode())
                .isEqualTo(200);

        // Sign in again PRESENTING the first session: the id must change. The
        // CSRF token of that signed-in session is the one that session holds.
        Csrf csrf = csrfFor(first);
        HttpResponse<String> again = post("/web/api/auth/login", body(user, PASSWORD),
                csrf.withSession(first), csrf.token());
        assertThat(again.statusCode()).isEqualTo(200);
        String second = cookie(again, SESSION_COOKIE).orElseThrow();

        assertThat(second).as("session fixation: a new id at every sign-in")
                .isNotEqualTo(first);
        assertThat(get("/web/api/auth/me", SESSION_COOKIE + "=" + first, null).statusCode())
                .as("the pre-login session is invalidated, not merely replaced")
                .isEqualTo(401);
        assertThat(get("/web/api/auth/me", SESSION_COOKIE + "=" + second, null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void meNeedsASessionAndReturnsNothingSecret() throws Exception {
        assertThat(get("/web/api/auth/me", null, null).statusCode()).isEqualTo(401);

        String user = newAccount();
        HttpResponse<String> me =
                get("/web/api/auth/me", SESSION_COOKIE + "=" + login(user), null);

        assertThat(me.statusCode()).isEqualTo(200);
        JsonNode json = mapper.readTree(me.body());
        assertThat(json.get("username").asText()).isEqualTo(user);
        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("username", "email", "emailVerified");
    }

    /* ------------------------------- CSRF ------------------------------ */

    @Test
    void aStateChangingPostWithoutACsrfTokenIsRefused() throws Exception {
        String user = newAccount();

        HttpResponse<String> noToken =
                post("/web/api/auth/login", body(user, PASSWORD), null, null);

        assertThat(noToken.statusCode()).as("no CSRF token at all").isEqualTo(403);
    }

    @Test
    void aCsrfTokenThatDoesNotMatchTheCookieIsRefused() throws Exception {
        String user = newAccount();
        Csrf csrf = bootstrapCsrf();

        HttpResponse<String> mismatched = post("/web/api/auth/login",
                body(user, PASSWORD), csrf.cookieHeader(), "not-the-right-token");

        assertThat(mismatched.statusCode()).isEqualTo(403);
    }

    @Test
    void logoutAlsoRequiresACsrfToken() throws Exception {
        String value = login(newAccount());

        assertThat(post("/web/api/auth/logout", null,
                SESSION_COOKIE + "=" + value, null).statusCode())
                .as("a signed-in session is not enough; the token is required too")
                .isEqualTo(403);
    }

    /* --------------------------- separation ---------------------------- */

    @Test
    void theWebsiteCookieAuthenticatesNothingOnTheApiChain() throws Exception {
        String session = SESSION_COOKIE + "=" + login(newAccount());

        assertThat(get("/api/auth/me", session, null).statusCode())
                .as("a website session must never open a desktop route")
                .isEqualTo(401);
        assertThat(get("/api/workflows/my-tasks", session, null).statusCode())
                .isEqualTo(401);
    }

    @Test
    void aValidBearerTokenIsIgnoredOnTheWebsiteChain() throws Exception {
        String user = newAccount();
        String accessToken = jwtService.issueAccessJwt(user);
        // Sanity: the very same token DOES work on the desktop chain.
        assertThat(get("/api/auth/me", null, accessToken).statusCode()).isEqualTo(200);

        assertThat(get("/web/api/auth/me", null, accessToken).statusCode())
                .as("a desktop/partner JWT must never open a website route")
                .isEqualTo(401);
    }

    /* ----------------------------- logout ------------------------------ */

    @Test
    void logoutInvalidatesTheSessionServerSide() throws Exception {
        String user = newAccount();
        String value = login(user);
        String session = SESSION_COOKIE + "=" + value;
        assertThat(sessionRowExists(value)).isTrue();

        Csrf csrf = csrfFor(value);
        HttpResponse<String> logout = post("/web/api/auth/logout", null,
                csrf.withSession(value), csrf.token());

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(get("/web/api/auth/me", session, null).statusCode())
                .as("the cookie the browser still holds authenticates nothing")
                .isEqualTo(401);
        assertThat(sessionRowExists(value))
                .as("the row is deleted, not just ignored").isFalse();
    }

    @Test
    void logoutWithoutASessionIsStillSuccessful() throws Exception {
        Csrf csrf = bootstrapCsrf();

        assertThat(post("/web/api/auth/logout", null, csrf.cookieHeader(), csrf.token())
                .statusCode()).isEqualTo(204);
    }

    /* -------------------- enumeration and lockout ---------------------- */

    @Test
    void anUnknownUserAndAWrongPasswordAreIndistinguishable() throws Exception {
        String user = newAccount();
        Csrf csrf = bootstrapCsrf();

        HttpResponse<String> wrongPassword = post("/web/api/auth/login",
                body(user, "definitely-not-the-password"), csrf.cookieHeader(), csrf.token());
        HttpResponse<String> unknownUser = post("/web/api/auth/login",
                body("no-such-account-" + UUID.randomUUID(), PASSWORD),
                csrf.cookieHeader(), csrf.token());

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownUser.statusCode()).isEqualTo(401);
        assertThat(unknownUser.body()).isEqualTo(wrongPassword.body());
        assertThat(cookie(wrongPassword, SESSION_COOKIE)).isEmpty();
        assertThat(cookie(unknownUser, SESSION_COOKIE)).isEmpty();
    }

    @Test
    void anInvitedPlaceholderCannotSignInAndLooksLikeAnyOtherFailure() throws Exception {
        String name = "p" + UUID.randomUUID().toString().substring(0, 8);
        UserEntity placeholder = new UserEntity();
        placeholder.setUsername(name);
        placeholder.setStatus(AccountStatus.PENDING_INVITE);
        users.save(placeholder);
        Csrf csrf = bootstrapCsrf();

        HttpResponse<String> response = post("/web/api/auth/login",
                body(name, PASSWORD), csrf.cookieHeader(), csrf.token());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(cookie(response, SESSION_COOKIE)).isEmpty();
    }

    @Test
    void accountLockoutIsSharedWithTheDesktopLogin() throws Exception {
        String user = newAccount();
        Csrf csrf = bootstrapCsrf();

        // Two failures on the website, two on the desktop API. Neither of the
        // first two carries a delay (people mistype their own password), so
        // all four are counted as they are made.
        for (int i = 0; i < 2; i++) {
            assertThat(post("/web/api/auth/login", body(user, "wrong-web-" + i),
                    csrf.cookieHeader(), csrf.token()).statusCode()).isEqualTo(401);
        }
        failOnTheApiAfterTheDelay(user, "wrong-api-0", Duration.ZERO);
        // The third failure costs 2 s and the fourth 8 s, and an attempt made
        // inside one of those is refused WITHOUT being counted — so a test
        // that fires five attempts back to back never reaches the fifth
        // failure. It used to pass only because the last two attempts landed
        // inside the 2 s refusal and were answered 401 by that, not by the
        // lock it was claiming to assert. On a slower run they landed outside
        // it, the count was still 3, and the correct password was accepted.
        failOnTheApiAfterTheDelay(user, "wrong-api-1", Duration.ofSeconds(2));

        // Four failures, from two different chains, in ONE row: this is the
        // property, and it is read from the counter rather than inferred from
        // a refusal, so no clock is involved.
        assertThat(count("SELECT count(*) FROM login_failures WHERE username = '"
                + user + "'"))
                .as("one counter, not one per chain").isEqualTo(1);
        assertThat(count("SELECT failed_count FROM login_failures WHERE username = '"
                + user + "'"))
                .as("website and desktop failures add up").isEqualTo(4);

        // The fifth is the lock the policy is configured for: lock-minutes,
        // not seconds, so what follows cannot depend on how fast this machine
        // is. It is made on the WEBSITE, against a count the desktop API
        // contributed half of.
        Thread.sleep(Duration.ofSeconds(8).plusMillis(250).toMillis());
        assertThat(post("/web/api/auth/login", body(user, "wrong-web-2"),
                csrf.cookieHeader(), csrf.token()).statusCode()).isEqualTo(401);

        // And now the CORRECT password is refused on both.
        assertThat(post("/api/auth/login", body(user, PASSWORD), null, null).statusCode())
                .as("lockout must not be per-chain").isEqualTo(401);
        assertThat(post("/web/api/auth/login", body(user, PASSWORD),
                csrf.cookieHeader(), csrf.token()).statusCode()).isEqualTo(401);
    }

    /**
     * A failed desktop sign-in, made after any progressive refusal from the
     * previous failure has expired, so that it is counted rather than
     * swallowed. The wait is the feature being waited out, not a guess.
     */
    private void failOnTheApiAfterTheDelay(String user, String wrongPassword,
                                           Duration previousDelay) throws Exception {
        if (!previousDelay.isZero()) {
            Thread.sleep(previousDelay.plusMillis(250).toMillis());
        }
        assertThat(post("/api/auth/login", body(user, wrongPassword), null, null)
                .statusCode()).isEqualTo(401);
    }

    /* ------------------- account status (risk C3) ---------------------- */

    @Test
    void disablingTheAccountEndsItsLiveSessionOnTheNextRequest() throws Exception {
        String user = newAccount();
        String value = login(user);
        String session = SESSION_COOKIE + "=" + value;
        assertThat(get("/web/api/auth/me", session, null).statusCode()).isEqualTo(200);
        assertThat(sessionRowExists(value)).isTrue();

        // An operator switches the account off while it is signed in.
        UserEntity account = users.findByUsername(user).orElseThrow();
        account.setStatus(AccountStatus.DISABLED);
        users.save(account);

        assertThat(get("/web/api/auth/me", session, null).statusCode())
                .as("a session must not outlive the account it belongs to")
                .isEqualTo(401);
        assertThat(sessionRowExists(value))
                .as("the session row is deleted, so the cookie is dead everywhere")
                .isFalse();
        // And it stays dead even if the account is switched back on.
        account.setStatus(AccountStatus.ACTIVE);
        users.save(account);
        assertThat(get("/web/api/auth/me", session, null).statusCode()).isEqualTo(401);
    }

    @Test
    void anInvitedPlaceholdersSessionIsRefusedToo() throws Exception {
        String user = newAccount();
        String session = SESSION_COOKIE + "=" + login(user);

        // Regressing the account to an invitation identity (no password) is
        // the other way isRegistered() turns false.
        UserEntity account = users.findByUsername(user).orElseThrow();
        account.setStatus(AccountStatus.PENDING_INVITE);
        users.save(account);

        assertThat(get("/web/api/auth/me", session, null).statusCode()).isEqualTo(401);
    }

    /* ------------------------- CSRF rotation --------------------------- */

    @Test
    void aSuccessfulLoginIssuesAFreshCsrfTokenAndRetiresTheOldOne() throws Exception {
        String user = newAccount();
        Csrf before = bootstrapCsrf();

        HttpResponse<String> response = post("/web/api/auth/login",
                body(user, PASSWORD), before.cookieHeader(), before.token());
        assertThat(response.statusCode()).isEqualTo(200);

        String after = cookie(response, CSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("login did not re-issue a CSRF cookie"));
        assertThat(after).as("the token a visitor arrived with is replaced at sign-in")
                .isNotEqualTo(before.token());

        String session = cookie(response, SESSION_COOKIE).orElseThrow();
        // The OLD token must no longer be accepted — replaying both halves of
        // the double submit does not help, because the session is the
        // authority on which token is in force.
        assertThat(post("/web/api/auth/logout", null,
                CSRF_COOKIE + "=" + before.token() + "; " + SESSION_COOKIE + "=" + session,
                before.token())
                .statusCode()).as("the pre-login CSRF token is retired").isEqualTo(403);
        // ...while the new one works.
        assertThat(post("/web/api/auth/logout", null,
                CSRF_COOKIE + "=" + after + "; " + SESSION_COOKIE + "=" + session, after)
                .statusCode()).isEqualTo(204);
    }

    @Test
    void theCsrfCookieIsAlsoSecureWhenTheSessionCookieIs() throws Exception {
        HttpResponse<String> response = get("/web/api/csrf", null, null);

        String line = setCookieLine(response, CSRF_COOKIE);
        assertThat(line).as("a CSRF token sent over plain HTTP defeats the whole check")
                .contains("Secure");
        assertThat(line).contains("SameSite=Lax");
        assertThat(line).contains("Path=/");
        // Still script-readable: the website has to echo it back.
        assertThat(line).doesNotContain("HttpOnly");
    }

    /* ------------------------------ logs ------------------------------- */

    @Test
    void noSessionIdPasswordOrCsrfTokenIsWrittenToTheLog(CapturedOutput output)
            throws Exception {
        String user = newAccount();
        Csrf csrf = bootstrapCsrf();
        HttpResponse<String> response = post("/web/api/auth/login",
                body(user, PASSWORD), csrf.cookieHeader(), csrf.token());
        String session = cookie(response, SESSION_COOKIE).orElseThrow();
        get("/web/api/auth/me", SESSION_COOKIE + "=" + session, null);
        // A FRESH bootstrap: the successful sign-in above invalidated the
        // pre-login session, so reusing its token would be refused by the
        // CSRF filter and never reach the controller that logs the failure.
        Csrf retry = bootstrapCsrf();
        post("/web/api/auth/login", body(user, "a-wrong-password"),
                retry.cookieHeader(), retry.token());

        String logged = output.getAll();
        assertThat(logged).doesNotContain(session);
        assertThat(logged).doesNotContain(csrf.token());
        assertThat(logged).doesNotContain(PASSWORD);
        assertThat(logged).doesNotContain("a-wrong-password");
        // The identifier appears only as a fingerprint on failure.
        assertThat(logged).contains("Website sign-in failed for identifier id#");
    }
}

package com.example.signer.server.security;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.db.repository.UserRepository;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase D1 risk D1: sign-up has its own tight, hour-long budget per
 * client address, so a script cannot fill {@code users} with rows for
 * addresses it does not control.
 *
 * <p>The limit is set to {@value #LIMIT} here. The shared per-address limiter
 * is left wide open (1000) on purpose: these tests must fail if the
 * <b>sign-up</b> throttle is missing, not pass because the general one
 * happened to fire.
 *
 * <p>Ordered, because the throttle is one bean keyed by this machine's
 * address: the test that measures the limit has to run before the one that
 * relies on the budget being spent.
 *
 * <p>Own throwaway database; skipped when PostgreSQL is not reachable.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.jwt-secret=" + WebSignUpThrottleHttpTests.SECRET,
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-web-signup-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + WebSignUpThrottleHttpTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                // Wide open, so only the sign-up throttle can refuse anything.
                "signature.remote-server.login.rate-limit=1000",
                "signature.remote-server.web.mail-limit=50",
                "signature.remote-server.web.signup-limit=3",
                "signature.remote-server.web.signup-window=PT1H",
                "signature.remote-server.web.session.cookie-name=__Host-SF_SESSION",
                "signature.remote-server.web.session.secure-cookie=true"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.security.WebSignUpThrottleHttpTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSignUpThrottleHttpTests {

    static final String DB = "signflow_web_signup_test";
    static final String SECRET =
            "web-signup-tests-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    /** Matches {@code signature.remote-server.web.signup-limit} above. */
    private static final int LIMIT = 3;

    private static final String SESSION_COOKIE = "__Host-SF_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String STRONG = "a-strong-password-9";

    private static Csrf shared;

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

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

    private record Csrf(String token, String session) {
        String cookieHeader() {
            String csrf = CSRF_COOKIE + "=" + token;
            return session == null ? csrf : csrf + "; " + SESSION_COOKIE + "=" + session;
        }
    }

    private static String newAddress() {
        return "s" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
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

    private Csrf csrf() throws Exception {
        if (shared == null) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(uri("/web/api/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(204);
            shared = new Csrf(cookie(response, CSRF_COOKIE).orElseThrow(),
                    cookie(response, SESSION_COOKIE).orElse(null));
        }
        return shared;
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        Csrf csrf = csrf();
        return http.send(HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .header("Cookie", csrf.cookieHeader())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> signUp(String address) throws Exception {
        return post("/web/api/auth/register", "{\"email\":\"" + address
                + "\",\"password\":\"" + STRONG + "\",\"displayName\":\"Ada\"}");
    }

    /* ------------------------------- tests ------------------------------ */

    @Test
    @Order(1)
    void signUpIsRefusedOnceTheAddressHasHadItsBudget() throws Exception {
        for (int i = 1; i <= LIMIT; i++) {
            String address = newAddress();
            assertThat(signUp(address).statusCode()).as("sign-up " + i).isEqualTo(202);
            assertThat(users.findByEmail(address)).as("sign-up " + i + " created its account")
                    .isPresent();
        }

        String refusedAddress = newAddress();
        HttpResponse<String> refused = signUp(refusedAddress);

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.body()).contains("TOO_MANY_REQUESTS");
        assertThat(refused.headers().firstValue("Retry-After"))
                .as("a 429 must say when to come back").isPresent();
        // The point of the throttle: no row is written for the refused one.
        assertThat(users.findByEmail(refusedAddress))
                .as("a throttled sign-up must not create an account").isEmpty();
        // The refusal says nothing about which limit was hit.
        assertThat(refused.body()).doesNotContain("sign-up", "signup", "address");
    }

    @Test
    @Order(2)
    void theThrottleCoversSignUpAndNothingElse() throws Exception {
        // Spent by the previous test, and still spent — the window is an hour.
        assertThat(signUp(newAddress()).statusCode()).isEqualTo(429);

        // Everything else an unverified or locked-out visitor needs still
        // works: the throttle must not become a way to deny the whole site.
        assertThat(post("/web/api/auth/resend-verification",
                "{\"email\":\"" + newAddress() + "\"}").statusCode()).isEqualTo(202);
        assertThat(post("/web/api/auth/password-reset/request",
                "{\"email\":\"" + newAddress() + "\"}").statusCode()).isEqualTo(202);
        assertThat(post("/web/api/auth/verify-email",
                "{\"token\":\"Zc3Kd9QwErTyUiOpAsDfGhJkZxCvBnM1234567890ab\"}").statusCode())
                .as("a verification attempt is not sign-up").isEqualTo(400);
        assertThat(post("/web/api/auth/login",
                "{\"username\":\"nobody\",\"password\":\"nothing\"}").statusCode())
                .as("signing in is not sign-up").isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(uri("/web/api/csrf")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode())
                .as("the CSRF bootstrap is not sign-up").isEqualTo(204);
    }
}

package com.example.signer.server.web;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.invites.ConsoleEmailService;

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
 * The limits around the Phase D account endpoints: three separate per-address
 * buckets, and a per-recipient cap on the e-mail they send.
 *
 * <p>Its own test class, with the address limit set to {@value #LIMIT},
 * because {@code LoginRateLimitFilter} is a shared singleton — flooding it
 * from here would otherwise spend the budget of every other website test.
 *
 * <p>The methods are <b>ordered</b> for the same reason: a bucket is one
 * piece of state for the whole class, so the test that fills one has to run
 * after the tests that need it empty. Every bucket here is keyed by the
 * client address, which is the loopback address for all of them.
 *
 * <p>Own throwaway database; skipped when PostgreSQL is not reachable.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.jwt-secret="
                        + WebAccountRateLimitHttpTests.SECRET,
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage="
                        + "${java.io.tmpdir}/signer-web-account-ratelimit-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + WebAccountRateLimitHttpTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.login.rate-limit=6",
                "signature.remote-server.login.rate-window-seconds=60",
                // Two account e-mails per recipient per window, so the third
                // is suppressed without having to send dozens.
                "signature.remote-server.web.mail-limit=2",
                // The sign-up throttle is measured by its own class; here it
                // must not be what refuses anything.
                "signature.remote-server.web.signup-limit=1000",
                // The test classpath activates dev, which renames the session
                // cookie; pin the production name so the CSRF bootstrap's
                // session can be carried back on the POSTs.
                "signature.remote-server.web.session.cookie-name=__Host-SF_SESSION",
                "signature.remote-server.web.session.secure-cookie=true"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.web.WebAccountRateLimitHttpTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebAccountRateLimitHttpTests {

    static final String DB = "signflow_web_account_rl_test";
    static final String SECRET =
            "web-account-rl-tests-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    /** Matches {@code signature.remote-server.login.rate-limit} above. */
    private static final int LIMIT = 6;

    private static final String SESSION_COOKIE = "__Host-SF_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String STRONG = "a-strong-password-9";

    /**
     * Bootstrapped once for the whole class: every {@code GET /web/api/csrf}
     * spends the bootstrap bucket, which the last test measures.
     */
    private static Csrf shared;

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ConsoleEmailService mailbox;

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
        return "r" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
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

    private HttpResponse<String> csrfBootstrap() throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/web/api/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The one CSRF token this class uses, fetched on first demand. */
    private Csrf csrf() throws Exception {
        if (shared == null) {
            HttpResponse<String> response = csrfBootstrap();
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

    private static void assertRefusedWithRetryAfter(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("TOO_MANY_REQUESTS");
        assertThat(response.headers().firstValue("Retry-After"))
                .as("a 429 must say when to come back").isPresent();
    }

    /* --------------------- the per-recipient mail cap ------------------- */

    /**
     * The e-mail cap is per <b>recipient</b>, so it cannot be used to tell
     * whether an address has an account: the answer stays 202 and only the
     * mailbox goes quiet.
     */
    @Test
    @Order(1)
    void overTheMailLimitTheAnswerIsStill202ButNoFurtherMailIsSent() throws Exception {
        String address = newAddress();

        assertThat(post("/web/api/auth/register",
                json("email", address, "password", STRONG, "displayName", "Ada"))
                .statusCode()).isEqualTo(202);
        String first = mailbox.latestAccountLink(address);
        assertThat(first).as("the sign-up link is the first mail").isNotNull();

        assertThat(post("/web/api/auth/resend-verification", json("email", address))
                .statusCode()).isEqualTo(202);
        String second = mailbox.latestAccountLink(address);
        assertThat(second).as("a resend issues a new link, so the mailbox changes")
                .isNotEqualTo(first);

        // The third is over the cap of two.
        HttpResponse<String> third =
                post("/web/api/auth/resend-verification", json("email", address));

        assertThat(third.statusCode())
                .as("being over the cap must look exactly like being under it")
                .isEqualTo(202);
        assertThat(third.body()).isEmpty();
        assertThat(mailbox.latestAccountLink(address))
                .as("no further e-mail is sent to that address").isEqualTo(second);
    }

    /* ------------------------ the per-address buckets ------------------- */

    /**
     * The account endpoints have their own address bucket, so a flood of
     * password-reset requests cannot lock the address out of signing in.
     */
    @Test
    @Order(2)
    void theAccountBucketIsFilledPerAddressAndLeavesTheSignInBudgetAlone() throws Exception {
        HttpResponse<String> refused = null;
        for (int i = 0; i < LIMIT + 2 && refused == null; i++) {
            // A fresh, unknown address each time, so the per-recipient cap
            // plays no part and no mail is sent.
            HttpResponse<String> response = post("/web/api/auth/password-reset/request",
                    json("email", newAddress()));
            if (response.statusCode() == 429) {
                refused = response;
            } else {
                assertThat(response.statusCode())
                        .as("every request under the limit answers 202").isEqualTo(202);
            }
        }

        assertThat(refused).as("the account endpoints must be limited per address").isNotNull();
        assertRefusedWithRetryAfter(refused);

        // The point of the separate bucket: signing in still has its own.
        assertThat(post("/web/api/auth/login", json("username", "nobody",
                "password", "nothing")).statusCode())
                .as("a flood of account requests must not spend the sign-in budget")
                .isEqualTo(401);
    }

    /**
     * {@code GET /web/api/csrf} is limited too (Phase C risk C9): it creates a
     * session row for an anonymous visitor, so an unlimited flood would fill
     * the session table.
     */
    @Test
    @Order(3)
    void theCsrfBootstrapIsLimitedPerAddressInItsOwnBucket() throws Exception {
        csrf(); // the one bootstrap this class shares is already spent
        HttpResponse<String> refused = null;
        for (int i = 0; i < LIMIT + 2 && refused == null; i++) {
            HttpResponse<String> response = csrfBootstrap();
            if (response.statusCode() == 429) {
                refused = response;
            } else {
                assertThat(response.statusCode()).isEqualTo(204);
            }
        }

        assertThat(refused).as("the CSRF bootstrap must be limited per address").isNotNull();
        assertRefusedWithRetryAfter(refused);

        // And that bucket is not the sign-in one either.
        assertThat(post("/web/api/auth/login", json("username", "nobody",
                "password", "nothing")).statusCode())
                .as("flooding the bootstrap must not spend the sign-in budget")
                .isEqualTo(401);
    }
}

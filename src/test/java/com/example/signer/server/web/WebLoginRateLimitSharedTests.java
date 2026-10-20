package com.example.signer.server.web;

import com.example.signer.server.WorkflowServerApplication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase C: the per-address sign-in limit is ONE budget across both
 * chains. Its own test class, because the limiter is a shared singleton whose
 * counter would otherwise be spent by the other website tests.
 *
 * <p>The limit is set to 4. Two attempts go to the desktop API and two to the
 * website; the fifth — on either path — must be refused. If each chain had
 * its own filter instance, alternating between them would buy twice the
 * attempts.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.jwt-secret="
                        + WebLoginRateLimitSharedTests.SECRET,
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-web-ratelimit-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + WebLoginRateLimitSharedTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.login.rate-limit=4",
                "signature.remote-server.login.rate-window-seconds=60",
                // The test classpath activates dev, which renames the session
                // cookie. Pin the production name so the helper below finds it
                // — without the session cookie the CSRF token cannot be loaded
                // and these attempts would be refused as 403 before reaching
                // the password check.
                "signature.remote-server.web.session.cookie-name=__Host-SF_SESSION",
                "signature.remote-server.web.session.secure-cookie=true"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.web.WebLoginRateLimitSharedTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class WebLoginRateLimitSharedTests {

    static final String DB = "signflow_web_ratelimit_test";
    static final String SECRET =
            "web-ratelimit-tests-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

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

    /** A sign-in attempt that is refused long before any password is checked. */
    private int attempt(String path, Csrf csrf) throws Exception {
        HttpRequest.Builder b = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"nobody\",\"password\":\"nothing\"}"));
        if (csrf != null) {
            b.header("Cookie", csrf.cookieHeader());
            b.header("X-XSRF-TOKEN", csrf.token());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /**
     * The CSRF token lives in the session, so the bootstrap's session cookie
     * has to travel with it — otherwise these attempts would be refused by
     * the CSRF filter (403) instead of reaching the password check (401).
     */
    private Csrf bootstrapCsrf() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest
                        .newBuilder(URI.create("http://localhost:" + port + "/web/api/csrf"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Csrf(value(response, "XSRF-TOKEN")
                .orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie")),
                value(response, "__Host-SF_SESSION").orElse(null));
    }

    private static java.util.Optional<String> value(HttpResponse<?> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .map(h -> h.substring(name.length() + 1).split(";", 2)[0])
                .filter(v -> !v.isEmpty())
                .findFirst();
    }

    private record Csrf(String token, String session) {
        String cookieHeader() {
            String csrf = "XSRF-TOKEN=" + token;
            return session == null ? csrf : csrf + "; __Host-SF_SESSION=" + session;
        }
    }

    @Test
    void theSignInBudgetIsSharedBetweenTheWebsiteAndTheDesktopApi() throws Exception {
        Csrf csrf = bootstrapCsrf();

        // 1 and 2 on the desktop API.
        assertThat(attempt("/api/auth/login", null)).isEqualTo(401);
        assertThat(attempt("/api/auth/login", null)).isEqualTo(401);
        // 3 and 4 on the website: same address, same budget.
        assertThat(attempt("/web/api/auth/login", csrf)).isEqualTo(401);
        assertThat(attempt("/web/api/auth/login", csrf)).isEqualTo(401);

        // The fifth is over the limit, whichever chain it arrives on.
        assertThat(attempt("/web/api/auth/login", csrf))
                .as("a website attempt must count against the same bucket")
                .isEqualTo(429);
        assertThat(attempt("/api/auth/login", null))
                .as("and the desktop API is limited by it too")
                .isEqualTo(429);
    }
}

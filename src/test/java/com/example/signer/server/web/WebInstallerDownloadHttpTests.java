package com.example.signer.server.web;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.auth.PasswordHashing;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signed-in installer route over real HTTP: who gets the file, who is
 * turned away, and what a browser is told.
 *
 * <p>This is the file that proves the requirement the unit tests cannot.
 * {@code WebInstallerDownloadTests} drives the controller directly, so it
 * never meets the filter chain — and the authorisation on this route <i>is</i>
 * the filter chain: {@code /web/api/**} ends in
 * {@code hasAuthority(ROLE_WEB_USER)} and there is no annotation on the
 * method. Nothing short of a booted server can show that an anonymous GET is
 * refused, which is why this test exists despite the cost of a Spring
 * context.
 *
 * <p>It also pins the one thing a reader would notice immediately if it
 * regressed: the public {@code /downloads/**} route is still open. That route
 * is how somebody invited to sign — who has no account yet — installs the
 * application, and adding a signed-in route must not have closed it.
 *
 * <p>Uses its own throwaway database, recreated for each run. Skipped when
 * PostgreSQL is not reachable.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + WebInstallerDownloadHttpTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.jwt-secret="
                        + "installer-tests-secret-0123456789-abcdefghijklmnopqrstuvwxyz",
                "signature.remote-server.login.rate-limit=1000",
                // The name under test: deliberately NOT the default, so a
                // passing assertion cannot be the default leaking through.
                "signature.remote-server.download.installer-name=SignFlow-Setup.msi"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.web.WebInstallerDownloadHttpTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class WebInstallerDownloadHttpTests {

    static final String DB = "signflow_web_installer_test";

    private static final String INSTALLER = "SignFlow-0.4.2.msi";

    private static final byte[] INSTALLER_BYTES = "pretend installer bytes".getBytes();

    private static final String PASSWORD = "correct-password-1";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String PATH = "/web/api/desktop/installer";

    @DynamicPropertySource
    static void downloads(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> tempDir("installer-store"));
        registry.add("signature.remote-server.download.dir", () -> {
            Path published = Path.of(tempDir("installer-published"));
            try {
                Files.write(published.resolve(INSTALLER), INSTALLER_BYTES);
                Files.writeString(published.resolve(INSTALLER + ".sha256"),
                        "c".repeat(64) + "  " + INSTALLER + "\n");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return published.toString();
        });
    }

    private static String tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

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

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private WebSessionProperties sessionProperties;

    /* ----------------------------- the tests ---------------------------- */

    @Test
    void withoutASessionThereIsNoInstaller() throws Exception {
        HttpResponse<byte[]> refused = this.http.send(
                HttpRequest.newBuilder(uri(PATH)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        // The filter chain, not the controller: this is the requirement that
        // the download is checked on the server and not only on the page.
        assertThat(refused.statusCode()).isEqualTo(401);
        // And not one byte of the file.
        assertThat(new String(refused.body(), StandardCharsets.UTF_8))
                .doesNotContain("pretend installer bytes");
    }

    @Test
    void aHeadWithoutASessionIsRefusedTheSameWay() throws Exception {
        // The page's preflight is a HEAD, so a chain that let HEAD through
        // would be a page that believes it may download and a 401 arriving
        // as a navigation. Both methods, one answer.
        HttpResponse<Void> refused = this.http.send(
                HttpRequest.newBuilder(uri(PATH)).method("HEAD",
                        HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(refused.statusCode()).isEqualTo(401);
    }

    @Test
    void aSignedInReaderGetsTheFileWithTheHeadersABrowserNeeds() throws Exception {
        String session = signIn(account());

        HttpResponse<byte[]> response = this.http.send(
                HttpRequest.newBuilder(uri(PATH))
                        .header("Cookie", this.sessionProperties.getCookieName() + "=" + session)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        // The bytes themselves, streamed from the folder.
        assertThat(response.body()).isEqualTo(INSTALLER_BYTES);
        // Saved, not displayed, and under the stable configured name rather
        // than the versioned one on disk.
        assertThat(response.headers().firstValue("content-disposition"))
                .hasValue("attachment; filename=\"SignFlow-Setup.msi\"");
        assertThat(response.headers().firstValue("content-type"))
                .hasValue("application/x-msi");
        assertThat(response.headers().firstValue("content-length"))
                .hasValue(String.valueOf(INSTALLER_BYTES.length));
        assertThat(response.headers().firstValue("x-content-type-options"))
                .hasValue("nosniff");
        // Authenticated, so no shared cache may keep it.
        assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
        // Nothing in the answer names the folder on this machine.
        assertThat(response.headers().map().toString()).doesNotContain("installer-published");
    }

    @Test
    void aPreflightFromASignedInReaderSaysGoAhead() throws Exception {
        String session = signIn(account());

        HttpResponse<Void> head = this.http.send(
                HttpRequest.newBuilder(uri(PATH))
                        .header("Cookie", this.sessionProperties.getCookieName() + "=" + session)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().firstValue("content-disposition"))
                .hasValue("attachment; filename=\"SignFlow-Setup.msi\"");
    }

    @Test
    void thePublicRouteIsStillPublic() throws Exception {
        // The invited signer's way in, unchanged. Adding a signed-in route
        // must not have closed the one somebody without an account needs.
        HttpResponse<byte[]> response = this.http.send(
                HttpRequest.newBuilder(uri("/downloads/" + INSTALLER)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(INSTALLER_BYTES);
        // And it keeps the versioned name, which is the whole reason the two
        // routes set a different Content-Disposition.
        assertThat(response.headers().firstValue("content-disposition"))
                .hasValue("attachment; filename=\"" + INSTALLER + "\"");
    }

    /* ------------------------------ helpers ----------------------------- */

    /** A verified account with a known password. */
    private String account() {
        String name = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserEntity account = new UserEntity(name, name + "@example.test",
                PasswordHashing.hash(PASSWORD), name, AccountStatus.ACTIVE);
        account.setEmailVerifiedAt(Instant.now());
        this.users.save(account);
        return name;
    }

    /** Signs in the way the website does, and returns the session cookie. */
    private String signIn(String username) throws Exception {
        HttpResponse<String> bootstrap = this.http.send(
                HttpRequest.newBuilder(uri("/web/api/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String token = cookie(bootstrap, CSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie"));
        String cookieName = this.sessionProperties.getCookieName();
        String header = CSRF_COOKIE + "=" + token
                + cookie(bootstrap, cookieName).map(s -> "; " + cookieName + "=" + s)
                        .orElse("");

        HttpResponse<String> login = this.http.send(
                HttpRequest.newBuilder(uri("/web/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", header)
                        .header("X-XSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"" + username + "\",\"password\":\""
                                        + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return cookie(login, cookieName)
                .orElseThrow(() -> new AssertionError("no session cookie"));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + this.port + path);
    }

    private static Optional<String> cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith(name + "="))
                .map(h -> h.substring(name.length() + 1).split(";", 2)[0])
                .filter(v -> !v.isEmpty())
                .findFirst();
    }
}

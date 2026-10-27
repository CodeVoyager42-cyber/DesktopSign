package com.example.signer.server.downloads;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The download, over real HTTP: the headers a browser needs, the 404 every
 * refusal turns into, and the page that offers it.
 *
 * <p>The path rules themselves are proved in {@link DownloadCatalogTests} —
 * there are more ways to spell a traversal than it is useful to send over a
 * socket, and the container rewrites several of them before a request
 * arrives. What this file adds is that the route is reachable with no
 * session at all, that a refusal is a 404 rather than a 500 or a 403, and
 * that nothing in an answer names a directory on this machine.
 */
@SpringBootTest(classes = com.example.signer.server.WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // The website's own filter chain only exists in database
                // mode (WebSecurityConfig is conditional on it), and
                // /web/api/download-info lives on that chain — so without a
                // database every /web/api/* path falls through to the bearer
                // chain and answers 401. Found the direct way: a probe showed
                // /web/api/csrf returning 401 in this context too.
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + DownloadHttpTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.jwt-secret="
                        + "download-tests-secret-0123456789-abcdefghijklmnopqrstuvwxyz"
        })
@ActiveProfiles("server")
@EnabledIf(value = "com.example.signer.server.downloads.DownloadHttpTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class DownloadHttpTests {

    static final String DB = "signflow_download_test";

    /** Recreates the throwaway database; false (skipped) when PostgreSQL is unreachable. */
    public static boolean freshDatabase() {
        String url = System.getenv().getOrDefault("SIGNER_DB_URL",
                "jdbc:postgresql://localhost:5432/postgres");
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, user, password);
             java.sql.Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + DB);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String INSTALLER = "SignFlow-0.4.2.msi";

    private static final String ZIP = "SignFlow-0.4.2-windows.zip";

    private static final byte[] INSTALLER_BYTES = "pretend installer bytes".getBytes();

    private static Path published;

    @DynamicPropertySource
    static void downloads(DynamicPropertyRegistry registry) {
        registry.add("signature.remote-server.storage", () -> tempDir("download-store"));
        registry.add("signature.remote-server.download.dir", () -> {
            published = Path.of(tempDir("download-published"));
            try {
                Files.write(published.resolve(INSTALLER), INSTALLER_BYTES);
                Files.writeString(published.resolve(INSTALLER + ".sha256"),
                        "b".repeat(64) + "  " + INSTALLER + "\n");
                Files.writeString(published.resolve(ZIP), "pretend zip");
                // A file that is not on the allow-list, in the same folder.
                Files.writeString(published.resolve("notes.txt"), "internal");
                // And a secret one level up, which is what traversal is for.
                Files.writeString(published.getParent().resolve("secret.txt"), "no");
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

    private static final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private String base;

    @LocalServerPort
    void capturePort(int port) {
        this.base = "http://localhost:" + port;
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(this.base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void theInstallerIsServedToAnyoneWithTheHeadersABrowserNeeds() throws Exception {
        HttpResponse<byte[]> response = get("/downloads/" + INSTALLER);

        // No session, no token, no CSRF: an invited signer has no account yet
        // and still needs the application that signs.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(INSTALLER_BYTES);
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"" + INSTALLER + "\"");
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValue("application/x-msi");
        // Declared, so nothing may second-guess it: an installer sniffed as
        // something else is a browser deciding to run it.
        assertThat(response.headers().firstValue("X-Content-Type-Options"))
                .hasValue("nosniff");
        assertThat(response.headers().firstValueAsLong("Content-Length"))
                .hasValue(INSTALLER_BYTES.length);
    }

    @Test
    void theChecksumAndTheZipAreServedWithTheirOwnTypes() throws Exception {
        assertThat(get("/downloads/" + INSTALLER + ".sha256").headers()
                .firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("text/plain"));
        assertThat(get("/downloads/" + ZIP).headers().firstValue("Content-Type"))
                .hasValue("application/zip");
    }

    @Test
    void aRangeRequestIsAnsweredSoALargeDownloadCanResume() throws Exception {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(this.base + "/downloads/" + INSTALLER))
                        .header("Range", "bytes=0-6")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        // 206 with the first seven bytes, from Spring's own resource
        // handling: for a 200 MB installer on a bad connection this is the
        // difference between resuming and starting again.
        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(new String(response.body())).isEqualTo("pretend");
    }

    @Test
    void everythingElseIs404AndSaysNothingAboutWhy() throws Exception {
        String[] refused = {
            // Not published.
            "/downloads/SignFlow-9.9.9.msi",
            // In the folder, but not on the allow-list.
            "/downloads/notes.txt",
            // Traversal, in the spellings a client can actually send.
            "/downloads/..%2fsecret.txt",
            "/downloads/..%5csecret.txt",
            "/downloads/%2e%2e%2fsecret.txt",
            "/downloads/SignFlow-0.4.2.msi.bak",
            "/downloads/",
        };
        for (String path : refused) {
            HttpResponse<byte[]> response = get(path);

            // 404 for every one of them: not 500 (the catalogue does not
            // throw), not 403 (which would confirm the file exists), and not
            // 401 (the route needs no session).
            assertThat(response.statusCode()).as(path).isIn(404, 400);
            String body = new String(response.body());
            // Nothing in the answer names a path on this machine.
            assertThat(body).as(path).doesNotContain(published.toString());
            assertThat(body).as(path).doesNotContain("secret");
        }
    }

    @Test
    void theDownloadPageIsRoutedPublicAndCarriesTheSecurityHeaders()
            throws Exception {
        HttpResponse<byte[]> page = get("/download");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(new String(page.body())).contains("<!DOCTYPE html>");
        // Its own document, not another page's.
        assertThat(new String(page.body()))
                .contains("/_next/static/chunks/app/download/page");
        // The same policy every page carries; the download page is no
        // exception just because it is public.
        assertThat(page.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(csp -> assertThat(csp)
                        .startsWith("default-src 'none'")
                        .contains("script-src 'self' 'sha256-"));
        assertThat(page.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
    }

    @Test
    void whatThereIsToDownloadIsPublicAndNotCached() throws Exception {
        HttpResponse<byte[]> info = get("/web/api/download-info");

        assertThat(info.statusCode()).isEqualTo(200);
        String body = new String(info.body());
        assertThat(body).contains("\"available\":true");
        assertThat(body).contains("\"url\":\"/downloads/" + INSTALLER + "\"");
        assertThat(body).contains("\"version\":\"0.4.2\"");
        assertThat(info.headers().firstValue("Cache-Control")).hasValue("no-store");
        // It describes a release; it must not describe the server's disk.
        assertThat(body).doesNotContain(published.toString());
    }

    @Test
    void theSignedInAppInfoStillNeedsASession() throws Exception {
        // Unchanged by this feature: /app-info is behind the session chain,
        // /download-info is the public one.
        assertThat(get("/web/api/app-info").statusCode()).isEqualTo(401);
    }
}

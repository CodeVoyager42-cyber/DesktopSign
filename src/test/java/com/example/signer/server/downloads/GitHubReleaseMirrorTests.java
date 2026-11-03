package com.example.signer.server.downloads;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mirror, end to end against a stub GitHub: a missing release file
 * appears in the download folder on the first request, verified against the
 * digest GitHub publishes, with its checksum sidecar written beside it —
 * and never anything that failed verification.
 */
class GitHubReleaseMirrorTests {

    private static final String FILE = "SignFlow-0.2.1.msi";

    private HttpServer server;

    private final AtomicInteger assetRequests = new AtomicInteger();

    @TempDir
    Path dir;

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
    }

    @Test
    void theLatestReleaseIsMirroredIntoTheFolderOnFirstUse() throws Exception {
        byte[] bytes = "the-installer-a-signer-will-receive".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        stubGithub(bytes, digest);
        GitHubReleaseMirror mirror = mirror();

        Optional<Path> file = mirror.mirrorFile(FILE);

        assertThat(file).isPresent();
        assertThat(Files.readAllBytes(file.get())).isEqualTo(bytes);
        // The sidecar is written out of the verified digest, in the same
        // shape scripts/package.sh writes.
        assertThat(Files.readString(dir.resolve(FILE + ".sha256"), StandardCharsets.UTF_8))
                .isEqualTo(digest + "  " + FILE + "\n");
        // And the catalog — the public download page's own source of truth —
        // now sees the mirrored release without any further wiring.
        assertThat(mirror.possible()).isTrue();
    }

    @Test
    void aSecondRequestIsServedFromTheFolderWithoutAnotherFetch() throws Exception {
        byte[] bytes = "downloaded-exactly-once".getBytes(StandardCharsets.UTF_8);
        stubGithub(bytes, sha256(bytes));
        GitHubReleaseMirror mirror = mirror();

        assertThat(mirror.mirrorFile(FILE)).isPresent();
        assertThat(mirror.mirrorFile(FILE)).isPresent();

        assertThat(this.assetRequests.get())
                .as("one upstream fetch, however many readers ask")
                .isEqualTo(1);
    }

    @Test
    void bytesThatFailVerificationPublishNothing() throws Exception {
        byte[] bytes = "bytes-not-matching-the-digest".getBytes(StandardCharsets.UTF_8);
        stubGithub(bytes, sha256("what-was-actually-published".getBytes(StandardCharsets.UTF_8)));
        GitHubReleaseMirror mirror = mirror();

        assertThat(mirror.mirrorFile(FILE)).isEmpty();
        try (var listing = Files.list(this.dir)) {
            assertThat(listing.toList())
                    .as("no artefact and no half-downloaded scratch file remains")
                    .isEmpty();
        }
    }

    @Test
    void aNameTheReleaseDoesNotPublishIsNotMirrored() throws Exception {
        byte[] bytes = "released".getBytes(StandardCharsets.UTF_8);
        stubGithub(bytes, sha256(bytes));
        GitHubReleaseMirror mirror = mirror();

        assertThat(mirror.mirrorFile("SignFlow-9.9.9.msi")).isEmpty();
        assertThat(this.assetRequests.get()).isZero();
    }

    @Test
    void theChecksumSidecarMirrorsItsBinaryFirst() throws Exception {
        byte[] bytes = "asked-for-the-checksum-alone".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        stubGithub(bytes, digest);
        GitHubReleaseMirror mirror = mirror();

        Optional<Path> sidecar = mirror.mirrorFile(FILE + ".sha256");

        assertThat(sidecar).isPresent();
        assertThat(Files.readString(sidecar.get(), StandardCharsets.UTF_8))
                .isEqualTo(digest + "  " + FILE + "\n");
        // The binary arrived with it: a checksum without its file is not a
        // state this folder should be able to enter by mirroring.
        assertThat(Files.readAllBytes(dir.resolve(FILE))).isEqualTo(bytes);
    }

    @Test
    void aSidecarAlreadyInTheFolderIsNotOverwritten() throws Exception {
        byte[] bytes = "the-operator-was-here-first".getBytes(StandardCharsets.UTF_8);
        stubGithub(bytes, sha256(bytes));
        Files.writeString(dir.resolve(FILE + ".sha256"), "operator's own record\n");
        GitHubReleaseMirror mirror = mirror();

        assertThat(mirror.mirrorFile(FILE + ".sha256")).isPresent();
        assertThat(Files.readString(dir.resolve(FILE + ".sha256"), StandardCharsets.UTF_8))
                .as("what a deployment published itself, it keeps")
                .isEqualTo("operator's own record\n");
    }

    @Test
    void withoutADownloadDirectoryThereIsNothingToMirror() throws Exception {
        byte[] bytes = "released".getBytes(StandardCharsets.UTF_8);
        stubGithub(bytes, sha256(bytes));
        // No dir configured: the catalog validates "" as "no downloads here".
        var client = new GitHubReleaseClient(base(), "acme/signflow", "token");
        var service = new LatestReleaseService(client, Duration.ofHours(1), Clock.systemUTC());
        var catalog = new DownloadCatalog("", "", "SignFlow");
        GitHubReleaseMirror mirror = new GitHubReleaseMirror(catalog, service, client);

        assertThat(mirror.possible()).isFalse();
        assertThat(mirror.mirrorFile(FILE)).isEmpty();
    }

    /* ------------------------------------------------------------------ */

    private GitHubReleaseMirror mirror() {
        var client = new GitHubReleaseClient(base(), "acme/signflow", "token");
        var service = new LatestReleaseService(client, Duration.ofHours(1), Clock.systemUTC());
        var catalog = new DownloadCatalog(this.dir.toString(), "", "SignFlow");
        return new GitHubReleaseMirror(catalog, service, client);
    }

    /** A stub GitHub: the release JSON naming one asset, and its bytes. */
    private void stubGithub(byte[] bytes, String digest) throws IOException {
        String json = """
                {"tag_name":"v0.2.1","published_at":"2026-10-28T13:53:14Z",
                 "assets":[{"id":77,"name":"%s","size":%d,"digest":"sha256:%s"}]}
                """.formatted(FILE, bytes.length, digest);
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/repos/acme/signflow/releases/latest")) {
                reply(exchange, json.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/repos/acme/signflow/releases/assets/77")) {
                this.assetRequests.incrementAndGet();
                reply(exchange, bytes);
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        this.server.start();
    }

    private String base() {
        return "http://localhost:" + this.server.getAddress().getPort();
    }

    private static void reply(HttpExchange exchange, byte[] payload) throws IOException {
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

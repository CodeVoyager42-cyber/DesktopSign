package com.example.signer.server.downloads;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire contract with GitHub, against a stub server: what is sent, what
 * is parsed, what a redirect must never carry, and what happens when the
 * answer is not the release it promised to be.
 */
class GitHubReleaseClientTests {

    private static final String RELEASE_JSON = """
            {"tag_name":"v0.2.1","published_at":"2026-10-28T13:53:14Z",
             "assets":[
               {"id":77,"name":"SignFlow-0.2.1.msi","size":120040043,
                "digest":"sha256:%s"},
               {"id":78,"name":"SignFlow-0.2.1-windows.zip","size":118713503,
                "digest":"sha256:%s"},
               {"id":79,"name":"SignFlow-0.2.1.msi.sha256","size":85}
             ]}
            """.formatted("a".repeat(64), "b".repeat(64));

    private HttpServer server;

    /** One entry per request this stub received: the Authorization header, or null. */
    private final List<String> seenAuthorizations =
            java.util.Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
    }

    @Test
    void theLatestReleaseIsReadFromTheJsonEndpoint() throws Exception {
        String base = serve(exchange -> {
            seen(exchange);
            reply(exchange, 200, RELEASE_JSON);
        });

        Optional<GitHubReleaseClient.Release> release =
                new GitHubReleaseClient(base, "acme/signflow", "token-abc").latest();

        assertThat(release).isPresent();
        assertThat(release.get().tag()).isEqualTo("v0.2.1");
        // The tag's leading v is not the artefacts' version: the names and
        // the page both want "0.2.1".
        assertThat(release.get().version()).isEqualTo("0.2.1");
        assertThat(release.get().publishedAt()).isNotNull();
        assertThat(release.get().asset("SignFlow-0.2.1.msi")).isPresent();
        assertThat(release.get().asset("SignFlow-0.2.1.msi").get().sha256())
                .isEqualTo("a".repeat(64));
        assertThat(release.get().installer()).isPresent();
        assertThat(release.get().portableZip()).isPresent();
        // The sidecar asset is parsed but never offered: its checksum is
        // written out of the binary's digest instead.
        assertThat(release.get().asset("SignFlow-0.2.1.msi.sha256")).isPresent();
        assertThat(seenAuthorizations).hasSize(1);
        assertThat(seenAuthorizations.get(0)).isEqualTo("Bearer token-abc");
    }

    @Test
    void assetsOutsideTheDownloadAllowListAreNeverOffered() throws Exception {
        String base = serve(exchange -> {
            seen(exchange);
            reply(exchange, 200, """
                    {"tag_name":"v1.0.0","assets":[
                      {"id":5,"name":"../../etc/passwd","size":10,"digest":"sha256:%s"},
                      {"id":6,"name":"notes.txt","size":10,"digest":"sha256:%s"},
                      {"id":7,"name":"SignFlow-1.0.0.msi","size":10,"digest":"sha256:%s"}
                    ]}
                    """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64)));
        });

        var release = new GitHubReleaseClient(base, "acme/signflow", "t").latest();

        assertThat(release).isPresent();
        assertThat(release.get().installer())
                .as("the one allow-listed installer is the only candidate")
                .isPresent()
                .hasValueSatisfying(asset -> assertThat(asset.name()).isEqualTo("SignFlow-1.0.0.msi"));
    }

    @Test
    void noReleaseYetIsAnAnswerNotAFailure() throws Exception {
        String base = serve(exchange -> {
            seen(exchange);
            reply(exchange, 404, "{}");
        });

        assertThat(new GitHubReleaseClient(base, "acme/signflow", "t").latest()).isEmpty();
    }

    @Test
    void aRateLimitOrServerErrorIsUnavailableNotSilent() throws Exception {
        String base = serve(exchange -> {
            seen(exchange);
            reply(exchange, 503, "{}");
        });

        assertThatThrownBy(() -> new GitHubReleaseClient(base, "acme/signflow", "t").latest())
                .isInstanceOf(GitHubReleaseClient.Unavailable.class);
    }

    @Test
    void withoutATokenNothingIsAsked() {
        GitHubReleaseClient client =
                new GitHubReleaseClient("https://api.github.invalid", "acme/signflow", "   ");

        assertThat(client.enabled()).isFalse();
        assertThat(client.latest()).isEmpty();
    }

    @Test
    void anAssetIsDownloadedThroughOneRedirectWithoutLeakingTheToken() throws Exception {
        byte[] bytes = "installer-bytes-for-the-mirror".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        String base = serve(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/releases/assets/77")) {
                seen(exchange);
                // GitHub's own shape: a signed link on the user-content
                // host, absolute, needing no credential.
                int port = exchange.getLocalAddress().getPort();
                exchange.getResponseHeaders().add("Location",
                        "http://localhost:" + port + "/asset-bytes");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if (path.equals("/asset-bytes")) {
                seen(exchange);
                replyBytes(exchange, bytes);
                return;
            }
            reply(exchange, 404, "{}");
        });

        GitHubReleaseClient client = new GitHubReleaseClient(base, "acme/signflow", "token-abc");
        var asset = new GitHubReleaseClient.Asset(77, "SignFlow-0.2.1.msi", bytes.length, digest);
        Path target = Files.createTempFile("mirror-test", ".bin");
        try {
            assertThat(client.downloadAsset(asset, target)).isTrue();
            assertThat(Files.readAllBytes(target)).isEqualTo(bytes);
            assertThat(seenAuthorizations).hasSize(2);
            assertThat(seenAuthorizations.get(0)).isEqualTo("Bearer token-abc");
            assertThat(seenAuthorizations.get(1))
                    .as("the redirect hop must carry no credential")
                    .isNull();
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void bytesThatDoNotMatchThePublishedDigestAreNeverAccepted() throws Exception {
        byte[] bytes = "totally-wrong-bytes".getBytes(StandardCharsets.UTF_8);
        String base = serve(exchange -> {
            seen(exchange);
            replyBytes(exchange, bytes);
        });

        GitHubReleaseClient client = new GitHubReleaseClient(base, "acme/signflow", "t");
        var asset = new GitHubReleaseClient.Asset(77, "SignFlow-0.2.1.msi",
                bytes.length, sha256("what-was-actually-published".getBytes(StandardCharsets.UTF_8)));
        Path target = Files.createTempFile("mirror-test", ".bin");
        try {
            assertThat(client.downloadAsset(asset, target)).isFalse();
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void aStreamLongerThanTheDeclaredSizeIsRefused() throws Exception {
        byte[] bytes = "more-bytes-than-were-declared".getBytes(StandardCharsets.UTF_8);
        String base = serve(exchange -> {
            seen(exchange);
            replyBytes(exchange, bytes);
        });

        GitHubReleaseClient client = new GitHubReleaseClient(base, "acme/signflow", "t");
        var asset = new GitHubReleaseClient.Asset(77, "SignFlow-0.2.1.msi",
                bytes.length - 1, sha256(bytes));
        Path target = Files.createTempFile("mirror-test", ".bin");
        try {
            assertThat(client.downloadAsset(asset, target)).isFalse();
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void anAssetWithoutAVerifiableDigestIsNotDownloadedAtAll() throws Exception {
        GitHubReleaseClient client =
                new GitHubReleaseClient("https://api.github.invalid", "acme/signflow", "t");
        var asset = new GitHubReleaseClient.Asset(77, "SignFlow-0.2.1.msi", 10, "");

        // No request, no file: bytes this server cannot check are bytes it
        // will not publish.
        assertThat(client.downloadAsset(asset, Path.of("never-written.tmp"))).isFalse();
    }

    /* ------------------------------------------------------------------ */

    private String serve(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.server.createContext("/", handler);
        this.server.start();
        return "http://localhost:" + this.server.getAddress().getPort();
    }

    private void seen(HttpExchange exchange) {
        this.seenAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        replyBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void replyBytes(HttpExchange exchange, byte[] payload) throws IOException {
        replyBytes(exchange, 200, payload);
    }

    private static void replyBytes(HttpExchange exchange, int status, byte[] payload)
            throws IOException {
        exchange.sendResponseHeaders(status, payload.length);
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

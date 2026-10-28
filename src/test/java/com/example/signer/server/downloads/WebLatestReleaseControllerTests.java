package com.example.signer.server.downloads;

import com.example.signer.server.web.WebLatestReleaseController;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /web/api/latest-release}, driven through the controller
 * directly (the routes themselves are covered by {@code DownloadHttpTests}
 * when a database is around): which source wins, what a page may branch on,
 * and that a click is only ever offered when this server can keep it.
 */
class WebLatestReleaseControllerTests {

    private static final GitHubReleaseClient.Release RELEASE = new GitHubReleaseClient.Release(
            "v0.2.1", "0.2.1", Instant.parse("2026-10-28T13:53:14Z"),
            List.of(new GitHubReleaseClient.Asset(77, "SignFlow-0.2.1.msi",
                            120_040_043, "a".repeat(64)),
                    new GitHubReleaseClient.Asset(78, "SignFlow-0.2.1-windows.zip",
                            118_713_503, "b".repeat(64))));

    @TempDir
    Path dir;

    @Test
    void githubWithAFolderOffersEveryReleasedArtefactOnThisServer() {
        // The folder exists but is empty: the mirror will fill it on the
        // first click, so the URL is a promise this deployment can keep.
        var view = controller(RELEASE, catalog(this.dir, "")).latestRelease().getBody();

        assertThat(view).isNotNull();
        assertThat(view.tag()).isEqualTo("v0.2.1");
        assertThat(view.version()).isEqualTo("0.2.1");
        assertThat(view.publishedAt()).isEqualTo("2026-10-28T13:53:14Z");
        assertThat(view.installer().fileName()).isEqualTo("SignFlow-0.2.1.msi");
        assertThat(view.installer().sizeBytes()).isEqualTo(120_040_043);
        assertThat(view.installer().sha256()).isEqualTo("a".repeat(64));
        assertThat(view.installer().url()).isEqualTo("/downloads/SignFlow-0.2.1.msi");
        assertThat(view.installer().checksumUrl())
                .isEqualTo("/downloads/SignFlow-0.2.1.msi.sha256");
        assertThat(view.portable().url()).isEqualTo("/downloads/SignFlow-0.2.1-windows.zip");
        assertThat(view.available()).isTrue();
    }

    @Test
    void githubIsTheTruthAboutMetadataEvenWhenAnOlderFileIsInTheFolder() throws IOException {
        // An operator's older copy: it must not dress up as the latest.
        Files.writeString(this.dir.resolve("SignFlow-0.2.0.msi"), "old installer");
        Files.writeString(this.dir.resolve("SignFlow-0.2.0.msi.sha256"),
                "c".repeat(64) + "  SignFlow-0.2.0.msi\n");

        var view = controller(RELEASE, catalog(this.dir, "")).latestRelease().getBody();

        assertThat(view).isNotNull();
        assertThat(view.version()).isEqualTo("0.2.1");
        assertThat(view.installer().fileName()).isEqualTo("SignFlow-0.2.1.msi");
        assertThat(view.installer().sizeBytes()).isEqualTo(120_040_043);
        assertThat(view.installer().sha256()).isEqualTo("a".repeat(64));
    }

    @Test
    void githubWithoutAFolderSaysTheTruthButOffersNoClick() {
        // No download folder: mirroring cannot work, the bytes are not here,
        // and no external link is configured — so no button, but the release
        // is still described rather than hidden.
        var view = controller(RELEASE, catalog(null, "")).latestRelease().getBody();

        assertThat(view).isNotNull();
        assertThat(view.version()).isEqualTo("0.2.1");
        assertThat(view.installer().fileName()).isEqualTo("SignFlow-0.2.1.msi");
        assertThat(view.installer().sha256()).isEqualTo("a".repeat(64));
        assertThat(view.installer().url()).isNull();
        assertThat(view.installer().checksumUrl()).isNull();
        assertThat(view.available()).isFalse();
    }

    @Test
    void anExternalLinkIsTheInstallerFallbackWhenNothingIsServableHere() {
        var view = controller(RELEASE, catalog(null, "https://downloads.example.test/SignFlow.msi"))
                .latestRelease().getBody();

        assertThat(view).isNotNull();
        assertThat(view.available()).isTrue();
        assertThat(view.installer().url())
                .isEqualTo("https://downloads.example.test/SignFlow.msi");
        // No sidecar rides along with a link this server does not serve.
        assertThat(view.installer().checksumUrl()).isNull();
        assertThat(view.portable().url()).as("the zip has no external fallback").isNull();
    }

    @Test
    void withoutGithubTheFolderIsTheWholeTruth() throws IOException {
        Files.writeString(this.dir.resolve("SignFlow-0.2.0.msi"), "installer bytes");
        Files.writeString(this.dir.resolve("SignFlow-0.2.0.msi.sha256"),
                "c".repeat(64) + "  SignFlow-0.2.0.msi\n");
        Files.writeString(this.dir.resolve("SignFlow-0.2.0-windows.zip"), "zip bytes");

        // The unconfigured deployment: GitHub is never asked, the folder
        // answers exactly as /web/api/download-info always has.
        var view = controller(null, catalog(this.dir, "")).latestRelease().getBody();

        assertThat(view).isNotNull();
        assertThat(view.tag()).isNull();
        assertThat(view.publishedAt()).isNull();
        assertThat(view.version()).isEqualTo("0.2.0");
        assertThat(view.installer().fileName()).isEqualTo("SignFlow-0.2.0.msi");
        assertThat(view.installer().sha256()).isEqualTo("c".repeat(64));
        assertThat(view.installer().url()).isEqualTo("/downloads/SignFlow-0.2.0.msi");
        assertThat(view.portable().url()).isEqualTo("/downloads/SignFlow-0.2.0-windows.zip");
        assertThat(view.available()).isTrue();
    }

    @Test
    void nothingAnywhereMeansUnavailableRatherThanBroken() {
        var view = controller(null, catalog(null, "")).latestRelease().getBody();

        assertThat(view).isNotNull();
        assertThat(view.available()).isFalse();
        assertThat(view.installer()).isNull();
        assertThat(view.portable()).isNull();
        assertThat(view.version()).isNull();
        assertThat(view.appName()).isEqualTo("SignFlow");
    }

    @Test
    void theAnswerIsNeverCached() {
        var response = controller(RELEASE, catalog(this.dir, "")).latestRelease();

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    /* ------------------------------------------------------------------ */

    private WebLatestReleaseController controller(GitHubReleaseClient.Release release,
            DownloadCatalog catalog) {
        // A client that answers once, forever: the service's own TTL and
        // stale logic are tested in LatestReleaseServiceTests.
        GitHubReleaseClient client = new FixedClient(release);
        var service = new LatestReleaseService(client, Duration.ofHours(1), Clock.systemUTC());
        var mirror = new GitHubReleaseMirror(catalog, service, client);
        return new WebLatestReleaseController(service, catalog, mirror);
    }

    private DownloadCatalog catalog(Path directory, String externalUrl) {
        return new DownloadCatalog(directory == null ? "" : directory.toString(),
                externalUrl, "SignFlow");
    }

    /** The release it was built with, or empty — never a failure. */
    private static final class FixedClient extends GitHubReleaseClient {
        private final Optional<GitHubReleaseClient.Release> release;

        FixedClient(GitHubReleaseClient.Release release) {
            super("https://api.github.invalid", "acme/signflow", "token");
            this.release = Optional.ofNullable(release);
        }

        @Override
        public Optional<GitHubReleaseClient.Release> latest() {
            return this.release;
        }
    }
}

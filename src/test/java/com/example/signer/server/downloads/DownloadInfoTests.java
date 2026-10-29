package com.example.signer.server.downloads;

import com.example.signer.server.web.WebAppInfoController;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three states {@code GET /web/api/download-info} has to describe, and
 * the one field a page is allowed to branch on.
 *
 * <p>Driven through the controller directly rather than over HTTP: what is
 * under test is which of the two sources wins and what `available` means,
 * and three Spring contexts would be three minutes to say the same thing.
 * The routes themselves are covered by {@code DownloadHttpTests}.
 */
class DownloadInfoTests {

    @TempDir
    Path published;

    private static WebAppInfoController controller(String dir, String url) {
        return new WebAppInfoController(new DownloadCatalog(dir, url, "SignFlow"));
    }

    @Test
    void aPublishedReleaseIsServedFromThisServer() throws IOException {
        Files.writeString(this.published.resolve("SignFlow-0.3.1.msi"), "installer bytes");
        Files.writeString(this.published.resolve("SignFlow-0.3.1.msi.sha256"),
                "a".repeat(64) + "  SignFlow-0.3.1.msi\n");
        Files.writeString(this.published.resolve("SignFlow-0.3.1-windows.zip"), "zip bytes");

        var info = controller(this.published.toString(), "").downloadInfo().getBody();

        assertThat(info).isNotNull();
        assertThat(info.available()).isTrue();
        assertThat(info.url()).isEqualTo("/downloads/SignFlow-0.3.1.msi");
        assertThat(info.fileName()).isEqualTo("SignFlow-0.3.1.msi");
        assertThat(info.version()).isEqualTo("0.3.1");
        assertThat(info.sizeBytes()).isEqualTo("installer bytes".length());
        assertThat(info.sha256()).isEqualTo("a".repeat(64));
        assertThat(info.portableUrl()).isEqualTo("/downloads/SignFlow-0.3.1-windows.zip");
        assertThat(info.appName()).isEqualTo("SignFlow");
    }

    @Test
    void aLocalReleaseWinsOverAConfiguredLink() throws IOException {
        Files.writeString(this.published.resolve("SignFlow-0.3.1.msi"), "installer bytes");

        var info = controller(this.published.toString(),
                "https://downloads.example.test/old.msi").downloadInfo().getBody();

        // A deployment that published a release said where the installer is
        // more recently, and more precisely, than a URL set once.
        assertThat(info).isNotNull();
        assertThat(info.url()).isEqualTo("/downloads/SignFlow-0.3.1.msi");
    }

    @Test
    void anExternalLinkIsUsedWhenNothingIsPublishedHere() {
        var info = controller("", "https://downloads.example.test/SignFlow.msi")
                .downloadInfo().getBody();

        assertThat(info).isNotNull();
        assertThat(info.available()).isTrue();
        assertThat(info.url()).isEqualTo("https://downloads.example.test/SignFlow.msi");
        // The server cannot know these about a file it does not hold, and
        // says so by leaving them empty rather than guessing.
        assertThat(info.sizeBytes()).isZero();
        assertThat(info.sha256()).isNull();
        assertThat(info.fileName()).isNull();
        assertThat(info.version()).isNull();
        assertThat(info.portableUrl()).isNull();
    }

    @Test
    void neitherMeansUnavailableRatherThanBroken() {
        var info = controller("", "").downloadInfo().getBody();

        assertThat(info).isNotNull();
        assertThat(info.available()).isFalse();
        assertThat(info.url()).isNull();
        // A deployment that has published nothing yet is a normal state, and
        // `available` is the single thing the page branches on.
        assertThat(info.appName()).isEqualTo("SignFlow");
    }

    @Test
    void theOlderAppInfoStillAnswersTheSameWay() throws IOException {
        Files.writeString(this.published.resolve("SignFlow-0.3.1.msi"), "installer bytes");

        // The hand-off screen's fallback reads this one, and both routes are
        // backed by the same catalogue so they cannot disagree.
        var appInfo = controller(this.published.toString(), "").appInfo().getBody();

        assertThat(appInfo).isNotNull();
        assertThat(appInfo.appName()).isEqualTo("SignFlow");
        assertThat(appInfo.downloadUrl()).isEqualTo("/downloads/SignFlow-0.3.1.msi");
    }

    @Test
    void appInfoHasNoLinkWhenThereIsNothingToLinkTo() {
        var appInfo = controller("", "").appInfo().getBody();

        assertThat(appInfo).isNotNull();
        assertThat(appInfo.downloadUrl()).isNull();
    }

    @Test
    void downloadInfoIsNotCached() throws IOException {
        Files.writeString(this.published.resolve("SignFlow-0.3.1.msi"), "installer bytes");

        var response = controller(this.published.toString(), "").downloadInfo();

        // Publishing a release is a file appearing in a folder, which is not
        // something a cache can be told about.
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}

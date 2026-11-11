package com.example.signer.server.downloads;

import com.example.signer.server.web.WebAppInfoController;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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

    private static final byte[] MSI_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };

    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 0x03, 0x04};

    private static byte[] withSignature(byte[] signature, String body) {
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = Arrays.copyOf(signature, signature.length + text.length);
        System.arraycopy(text, 0, bytes, signature.length, text.length);
        return bytes;
    }

    @TempDir
    Path published;

    private static WebAppInfoController controller(String dir, String url) {
        return new WebAppInfoController(new DownloadCatalog(dir, url, "SignFlow"));
    }

    private static final byte[] INSTALLER = withSignature(MSI_SIGNATURE, "installer bytes");

    private static final byte[] ZIP = withSignature(ZIP_SIGNATURE, "zip bytes");

    @Test
    void aPublishedReleaseIsServedFromThisServer() throws IOException {
        Files.write(this.published.resolve("SignFlow-0.3.1.msi"), INSTALLER);
        Files.writeString(this.published.resolve("SignFlow-0.3.1.msi.sha256"),
                "a".repeat(64) + "  SignFlow-0.3.1.msi\n");
        Files.write(this.published.resolve("SignFlow-0.3.1-windows.zip"), ZIP);

        var info = controller(this.published.toString(), "").downloadInfo().getBody();

        assertThat(info).isNotNull();
        assertThat(info.available()).isTrue();
        assertThat(info.url()).isEqualTo("/downloads/SignFlow-0.3.1.msi");
        assertThat(info.fileName()).isEqualTo("SignFlow-0.3.1.msi");
        assertThat(info.version()).isEqualTo("0.3.1");
        assertThat(info.sizeBytes()).isEqualTo(INSTALLER.length);
        assertThat(info.sha256()).isEqualTo("a".repeat(64));
        assertThat(info.portableUrl()).isEqualTo("/downloads/SignFlow-0.3.1-windows.zip");
        assertThat(info.appName()).isEqualTo("SignFlow");
    }

    @Test
    void aLocalReleaseWinsOverAConfiguredLink() throws IOException {
        Files.write(this.published.resolve("SignFlow-0.3.1.msi"), INSTALLER);

        var info = controller(this.published.toString(),
                "https://downloads.example.test/old.msi").downloadInfo().getBody();

        // A deployment that published a release said where the installer is
        // more recently, and more precisely, than a URL set once.
        assertThat(info).isNotNull();
        assertThat(info.url()).isEqualTo("/downloads/SignFlow-0.3.1.msi");
    }

    @Test
    void aPortableOnlyReleaseIsStillADownload() throws IOException {
        // What a deployment without WiX can produce: jpackage makes the zip
        // with no toolchain at all, and the MSI not at all. The zip is a real
        // release, so the page must offer it rather than say nothing is
        // published while the file sits in the folder.
        Files.write(this.published.resolve("SignFlow-0.3.1-windows.zip"), ZIP);
        Files.writeString(this.published.resolve("SignFlow-0.3.1-windows.zip.sha256"),
                "b".repeat(64) + "  SignFlow-0.3.1-windows.zip\n");

        var info = controller(this.published.toString(), "").downloadInfo().getBody();

        assertThat(info).isNotNull();
        assertThat(info.available()).isTrue();
        assertThat(info.url()).isEqualTo("/downloads/SignFlow-0.3.1-windows.zip");
        assertThat(info.fileName()).isEqualTo("SignFlow-0.3.1-windows.zip");
        assertThat(info.version()).isEqualTo("0.3.1");
        assertThat(info.sizeBytes()).isEqualTo(ZIP.length);
        assertThat(info.sha256()).isEqualTo("b".repeat(64));
        // The one button already leads to the zip; it is not also the
        // alternative beside itself.
        assertThat(info.portableUrl()).isNull();
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
        Files.write(this.published.resolve("SignFlow-0.3.1.msi"), INSTALLER);

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
        Files.write(this.published.resolve("SignFlow-0.3.1.msi"), INSTALLER);

        var response = controller(this.published.toString(), "").downloadInfo();

        // Publishing a release is a file appearing in a folder, which is not
        // something a cache can be told about.
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}

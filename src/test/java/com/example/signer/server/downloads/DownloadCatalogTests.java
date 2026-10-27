package com.example.signer.server.downloads;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a request may name, and what it may not.
 *
 * <p>This is where the download's path handling is proved, because it is the
 * only place path handling exists: {@code DownloadController} turns an empty
 * answer into a 404 and does nothing else. A name that gets past
 * {@link DownloadCatalog#resolve} is a file that gets served, so the cases
 * below are the real boundary — allow-list, directory, regular file,
 * symlink — rather than a sample of it.
 */
class DownloadCatalogTests {

    @TempDir
    Path published;

    private DownloadCatalog catalog;

    @BeforeEach
    void publishARelease() throws IOException {
        Files.writeString(this.published.resolve("SignFlow-0.1.0.msi"), "pretend installer");
        Files.writeString(this.published.resolve("SignFlow-0.1.0.msi.sha256"),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  SignFlow-0.1.0.msi\n");
        Files.writeString(this.published.resolve("SignFlow-0.1.0-windows.zip"), "pretend zip");
        this.catalog = catalog(this.published.toString(), "");
    }

    private static DownloadCatalog catalog(String dir, String url) {
        return new DownloadCatalog(dir, url, "SignFlow");
    }

    @Test
    void theInstallerIsFoundWithItsVersionSizeAndChecksum() {
        DownloadCatalog.Artefact installer = this.catalog.installer().orElseThrow();

        assertThat(installer.fileName()).isEqualTo("SignFlow-0.1.0.msi");
        assertThat(installer.version()).isEqualTo("0.1.0");
        assertThat(installer.sizeBytes()).isEqualTo("pretend installer".length());
        // Read from the .sha256 beside it, which is what package.sh writes —
        // nothing is hashed on demand, because a reader waiting on a 200 MB
        // read is a worse answer than "no checksum published".
        assertThat(installer.sha256())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        assertThat(this.catalog.portableZip().orElseThrow().fileName())
                .isEqualTo("SignFlow-0.1.0-windows.zip");
    }

    @Test
    void aMangledChecksumFileIsNoChecksumAtAll() throws IOException {
        Files.writeString(this.published.resolve("SignFlow-0.1.0.msi.sha256"),
                "this is not a hash");

        // Rather than putting arbitrary text from a file on a page.
        assertThat(this.catalog.installer().orElseThrow().sha256()).isNull();
    }

    @Test
    void theNewestInstallerWins() throws IOException {
        Path newer = this.published.resolve("SignFlow-0.2.0.msi");
        Files.writeString(newer, "newer installer");
        Files.setLastModifiedTime(newer,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        assertThat(this.catalog.installer().orElseThrow().fileName())
                .isEqualTo("SignFlow-0.2.0.msi");
    }

    @Test
    void aPublishedFileResolves() {
        assertThat(this.catalog.resolve("SignFlow-0.1.0.msi")).isPresent();
        assertThat(this.catalog.resolve("SignFlow-0.1.0.msi.sha256")).isPresent();
        assertThat(this.catalog.resolve("SignFlow-0.1.0-windows.zip")).isPresent();
    }

    @Test
    void everyWayOfNamingSomethingElseIsRefused() throws IOException {
        // A secret one directory up — the thing traversal would be for.
        Files.writeString(this.published.getParent().resolve("secrets.txt"), "no");

        String[] attempts = {
            "../secrets.txt",
            "..\\secrets.txt",
            "../../../../Windows/System32/config/SAM",
            // Already decoded by the container by the time it reaches here;
            // included because it is what an attacker sends.
            "%2e%2e%2fsecrets.txt",
            "..%5csecrets.txt",
            "/etc/passwd",
            "C:\\Windows\\win.ini",
            "\\\\server\\share\\file.msi",
            "sub/SignFlow-0.1.0.msi",
            "SignFlow-0.1.0.msi/../../secrets.txt",
            // Not on the allow-list, however harmless it looks.
            "readme.txt",
            "SignFlow-0.1.0.exe",
            "signflow-0.1.0.msi",
            "SignFlow-0.1.0.msi.bak",
            "",
            ".",
            "..",
        };
        for (String attempt : attempts) {
            assertThat(this.catalog.resolve(attempt))
                    .as("resolve(%s)", attempt)
                    .isEmpty();
        }
        assertThat(this.catalog.resolve(null)).isEmpty();
    }

    @Test
    void aDirectoryWithAnAllowedNameIsNotAFile() throws IOException {
        Files.createDirectory(this.published.resolve("SignFlow-9.9.9.msi"));

        assertThat(this.catalog.resolve("SignFlow-9.9.9.msi")).isEmpty();
    }

    @Test
    void aSymbolicLinkOutOfTheDirectoryIsRefused() throws IOException {
        Path secret = this.published.getParent().resolve("secret.msi");
        Files.writeString(secret, "not for you");
        Path link = this.published.resolve("SignFlow-6.6.6.msi");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows needs a privilege for this; the check it proves is
            // still in resolve(), and the test says nothing rather than
            // passing for the wrong reason.
            org.junit.jupiter.api.Assumptions.abort(
                    "symbolic links are not available to this process");
            return;
        }

        // The name passes the pattern and the file is real; what fails is the
        // real path's parent.
        assertThat(this.catalog.resolve("SignFlow-6.6.6.msi")).isEmpty();
    }

    @Test
    void noDirectoryMeansNothingToServeAndNothingToSay() {
        DownloadCatalog none = catalog("", "");

        assertThat(none.installer()).isEmpty();
        assertThat(none.portableZip()).isEmpty();
        assertThat(none.externalUrl()).isEmpty();
        assertThat(none.resolve("SignFlow-0.1.0.msi")).isEmpty();
    }

    @Test
    void anExternalUrlStandsInWhenThereIsNoDirectory() {
        DownloadCatalog external = catalog("", "https://downloads.example.test/SignFlow.msi");

        assertThat(external.externalUrl())
                .contains("https://downloads.example.test/SignFlow.msi");
        assertThat(external.installer()).isEmpty();
    }

    @Test
    void aDirectoryThatIsSetButWrongFailsStartup() {
        Path missing = this.published.resolve("not-created");

        // Fail fast, with a sentence somebody can act on — rather than
        // serving 404s that read as "no release published yet".
        assertThatThrownBy(() -> catalog(missing.toString(), ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIGNER_DOWNLOAD_DIR")
                .hasMessageContaining("not an existing directory");
    }

    @Test
    void aFileWhereTheDirectoryShouldBeFailsStartupToo() throws IOException {
        Path file = this.published.resolve("SignFlow-0.1.0.msi");

        assertThatThrownBy(() -> catalog(file.toString(), ""))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.isRegularFile(file)).isTrue();
    }

    @Test
    void theDirectoryIsReadLazilySoAReleaseCanBePublishedWithoutARestart()
            throws IOException {
        DownloadCatalog fresh = catalog(this.published.toString(), "");
        Optional<DownloadCatalog.Artefact> before = fresh.installer();
        assertThat(before).isPresent();

        Files.delete(this.published.resolve("SignFlow-0.1.0.msi"));

        // Nothing is cached: the folder is the source of truth, so copying a
        // file in or out is all publishing takes.
        assertThat(fresh.installer()).isEmpty();
    }
}

package com.example.signer.server.downloads;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
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

    private static final byte[] MSI_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };

    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 0x03, 0x04};

    private static final byte[] EMPTY_ZIP_SIGNATURE = {'P', 'K', 0x05, 0x06};

    @TempDir
    Path published;

    private DownloadCatalog catalog;

    @BeforeEach
    void publishARelease() throws IOException {
        writeWithSignature(this.published.resolve("SignFlow-0.1.0.msi"),
                MSI_SIGNATURE, "pretend installer");
        Files.writeString(this.published.resolve("SignFlow-0.1.0.msi.sha256"),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  SignFlow-0.1.0.msi\n");
        writeWithSignature(this.published.resolve("SignFlow-0.1.0-windows.zip"),
                ZIP_SIGNATURE, "pretend zip");
        this.catalog = catalog(this.published.toString(), "");
    }

    /** The bytes a real package of this format would start with. */
    private static void writeWithSignature(Path file, byte[] signature, String body)
            throws IOException {
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = Arrays.copyOf(signature, signature.length + text.length);
        System.arraycopy(text, 0, bytes, signature.length, text.length);
        Files.write(file, bytes);
    }

    private static DownloadCatalog catalog(String dir, String url) {
        return new DownloadCatalog(dir, url, "SignFlow");
    }

    @Test
    void theInstallerIsFoundWithItsVersionSizeAndChecksum() {
        DownloadCatalog.Artefact installer = this.catalog.installer().orElseThrow();

        assertThat(installer.fileName()).isEqualTo("SignFlow-0.1.0.msi");
        assertThat(installer.version()).isEqualTo("0.1.0");
        assertThat(installer.sizeBytes())
                .isEqualTo(MSI_SIGNATURE.length + "pretend installer".length());
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
        writeWithSignature(newer, MSI_SIGNATURE, "newer installer");
        Files.setLastModifiedTime(newer,
                FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        assertThat(this.catalog.installer().orElseThrow().fileName())
                .isEqualTo("SignFlow-0.2.0.msi");
    }

    @Test
    void aTextFileWithAnMsiNameIsNotOffered() throws IOException {
        // The placeholder that made the download page offer a 159-byte text
        // file. It is newer than the real release, so only the signature
        // check can keep it off the page.
        Path placeholder = this.published.resolve("SignFlow-0.0.0-PLACEHOLDER.msi");
        Files.writeString(placeholder, "This is NOT a real installer.");
        Files.setLastModifiedTime(placeholder,
                FileTime.fromMillis(System.currentTimeMillis() + 120_000));

        assertThat(this.catalog.installer().orElseThrow().fileName())
                .isEqualTo("SignFlow-0.1.0.msi");
        // Nor can it be fetched by guessing its name.
        assertThat(this.catalog.resolve("SignFlow-0.0.0-PLACEHOLDER.msi")).isEmpty();

        Files.delete(this.published.resolve("SignFlow-0.1.0.msi"));
        assertThat(this.catalog.installer())
                .as("a folder holding only the placeholder offers nothing")
                .isEmpty();
    }

    @Test
    void aTextFileWithAZipNameIsNotOfferedEither() throws IOException {
        Path placeholder = this.published.resolve("SignFlow-0.0.0-windows.zip");
        Files.writeString(placeholder, "not a zip");
        Files.setLastModifiedTime(placeholder,
                FileTime.fromMillis(System.currentTimeMillis() + 120_000));

        assertThat(this.catalog.portableZip().orElseThrow().fileName())
                .isEqualTo("SignFlow-0.1.0-windows.zip");
        assertThat(this.catalog.resolve("SignFlow-0.0.0-windows.zip")).isEmpty();
    }

    @Test
    void aRejectedFileResolvesToEmptyInsteadOfThrowing() throws IOException {
        Path placeholder = this.published.resolve("SignFlow-0.0.0-PLACEHOLDER.msi");
        Files.writeString(placeholder, "This is NOT a real installer.");

        // The name is on the allow-list; only the content gate rejects it, and
        // resolve must answer empty — the controller's 404 — not throw.
        assertThat(DownloadCatalog.isAllowed("SignFlow-0.0.0-PLACEHOLDER.msi")).isTrue();
        assertThat(this.catalog.resolve("SignFlow-0.0.0-PLACEHOLDER.msi")).isEmpty();
    }

    @Test
    void aTruncatedMsiHeaderIsRejected() throws IOException {
        Path truncated = this.published.resolve("SignFlow-0.3.0.msi");
        Files.write(truncated, Arrays.copyOf(MSI_SIGNATURE, 4));
        makeNewer(truncated);

        assertThat(this.catalog.installer().orElseThrow().fileName())
                .isEqualTo("SignFlow-0.1.0.msi");
        assertThat(this.catalog.resolve("SignFlow-0.3.0.msi")).isEmpty();
    }

    @Test
    void aZipPayloadWithAnMsiNameIsRejected() throws IOException {
        Path wrongMagic = this.published.resolve("SignFlow-0.3.0.msi");
        writeWithSignature(wrongMagic, ZIP_SIGNATURE, "a zip wearing an msi name");
        makeNewer(wrongMagic);

        assertThat(this.catalog.installer().orElseThrow().fileName())
                .isEqualTo("SignFlow-0.1.0.msi");
        assertThat(this.catalog.resolve("SignFlow-0.3.0.msi")).isEmpty();
    }

    @Test
    void anEmptyZipIsNotOfferedAsAPortableBuild() throws IOException {
        Path empty = this.published.resolve("SignFlow-0.3.0-windows.zip");
        // A valid empty archive: just the end-of-central-directory record.
        byte[] bytes = new byte[22];
        System.arraycopy(EMPTY_ZIP_SIGNATURE, 0, bytes, 0, EMPTY_ZIP_SIGNATURE.length);
        Files.write(empty, bytes);
        makeNewer(empty);

        assertThat(this.catalog.portableZip().orElseThrow().fileName())
                .isEqualTo("SignFlow-0.1.0-windows.zip");
        assertThat(this.catalog.resolve("SignFlow-0.3.0-windows.zip")).isEmpty();
    }

    @Test
    void aFileStartingWithTheRealMsiHeaderIsStillOffered() throws IOException {
        Path genuine = this.published.resolve("SignFlow-0.3.0.msi");
        Files.write(genuine, MSI_SIGNATURE);
        makeNewer(genuine);

        // The gate checks only the signature: a real header must not be
        // refused because the rest of the package is elsewhere.
        assertThat(this.catalog.installer().orElseThrow().fileName())
                .isEqualTo("SignFlow-0.3.0.msi");
        assertThat(this.catalog.resolve("SignFlow-0.3.0.msi")).isPresent();
    }

    /** Makes a file newer than the release published by the fixture. */
    private static void makeNewer(Path file) throws IOException {
        Files.setLastModifiedTime(file,
                FileTime.fromMillis(System.currentTimeMillis() + 120_000));
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

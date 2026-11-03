package com.example.signer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-detection of the Gemalto/eBarid Sign PKCS#11 driver: an explicit
 * {@code PKCS11_LIBRARY} always wins; a blank one scans the standard
 * locations so end users need no configuration.
 */
class Pkcs11LibraryResolverTests {

    @TempDir
    Path dir1;

    @TempDir
    Path dir2;

    private void driver(Path dir, String name) throws IOException {
        Files.writeString(dir.resolve(name), "fake driver");
    }

    @Test
    void anExplicitlyConfiguredLibraryAlwaysWins() {
        assertThat(Pkcs11LibraryResolver.resolve(
                "vendor-pkcs11.dll", List.of(dir1.toString())))
                .isEqualTo("vendor-pkcs11.dll");
        // Even a nonexistent configured path is returned untouched: the
        // consumers report that fact themselves.
        assertThat(Pkcs11LibraryResolver.resolve(
                "C:\\no\\such\\dir\\driver.dll", List.of(dir1.toString())))
                .isEqualTo("C:\\no\\such\\dir\\driver.dll");
        assertThat(Pkcs11LibraryResolver.resolve("  ", List.of(dir1.toString())))
                .as("blank config means auto-detect")
                .isEqualTo("");
    }

    @Test
    void theKnownGemaltoDriversAreFoundByTheirStandardNames() throws IOException {
        driver(dir1, "eTPKCS11.dll");
        assertThat(Pkcs11LibraryResolver.resolve("", List.of(dir1.toString())))
                .isEqualTo(dir1.resolve("eTPKCS11.dll").toString());

        // Fresh directory for gclib so the first file is not what matched.
        driver(dir2, "gclib.dll");
        assertThat(Pkcs11LibraryResolver.resolve("", List.of(dir2.toString())))
                .isEqualTo(dir2.resolve("gclib.dll").toString());
    }

    @Test
    void whenBothDriversExistTheFirstCandidateWins() throws IOException {
        driver(dir1, "gclib.dll");
        driver(dir1, "eTPKCS11.dll");
        assertThat(Pkcs11LibraryResolver.resolve("", List.of(dir1.toString())))
                .as("eTPKCS11.dll is scanned before gclib.dll")
                .isEqualTo(dir1.resolve("eTPKCS11.dll").toString());
    }

    @Test
    void directoriesAreScannedInTheGivenOrder() throws IOException {
        driver(dir1, "gclib.dll");   // only dir1 has a driver
        driver(dir2, "eTPKCS11.dll");
        assertThat(Pkcs11LibraryResolver.resolve("", List.of(dir1.toString(), dir2.toString())))
                .isEqualTo(dir1.resolve("gclib.dll").toString());
        assertThat(Pkcs11LibraryResolver.resolve("", List.of(dir2.toString(), dir1.toString())))
                .isEqualTo(dir2.resolve("eTPKCS11.dll").toString());
    }

    @Test
    void anEmptyScanFindsNothing() {
        assertThat(Pkcs11LibraryResolver.resolve("", List.of(dir1.toString()))).isEmpty();
        assertThat(Pkcs11LibraryResolver.resolve(null, List.of())).isEmpty();
    }
}

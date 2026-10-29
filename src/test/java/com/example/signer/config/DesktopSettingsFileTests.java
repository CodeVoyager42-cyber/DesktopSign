package com.example.signer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-machine override an IT department can drop next to an installed
 * SignFlow, so one MSI can be pointed at a different coordination server
 * without rebuilding it.
 */
class DesktopSettingsFileTests {

    @Test
    void noFileMeansNoOverride(@TempDir Path dir) {
        assertThat(DesktopSettingsFile.read(dir.resolve("settings.properties"))).isEmpty();
        assertThat(DesktopSettingsFile.read(null)).isEmpty();
    }

    /** A directory where a file was expected is not an override either. */
    @Test
    void aDirectoryIsNotASettingsFile(@TempDir Path dir) {
        assertThat(DesktopSettingsFile.read(dir)).isEmpty();
    }

    @Test
    void anHttpsAddressIsAccepted(@TempDir Path dir) throws Exception {
        Path file = write(dir, "signature.remote.base-url=https://internal.example.com");

        Map<String, Object> overrides = DesktopSettingsFile.read(file);

        assertThat(overrides)
                .containsEntry("signature.remote.base-url", "https://internal.example.com");
    }

    @Test
    void anEmptyFileIsHarmless(@TempDir Path dir) throws Exception {
        assertThat(DesktopSettingsFile.read(write(dir, "# nothing to see here"))).isEmpty();
    }

    /**
     * The address is checked by the same rules the build applies. A bad value
     * fails start-up rather than being ignored: falling back silently would
     * leave an administrator believing the override had taken effect.
     */
    @Test
    void anAddressThatBreaksTheRulesFailsStartUp(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> DesktopSettingsFile.read(
                write(dir, "signature.remote.base-url=https://internal.example.com/api")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signature.remote.base-url")
                .hasMessageContaining("no path");

        assertThatThrownBy(() -> DesktopSettingsFile.read(
                write(dir, "signature.remote.base-url=https://internal.example.com/")))
                .hasMessageContaining("trailing slash");

        assertThatThrownBy(() -> DesktopSettingsFile.read(
                write(dir, "signature.remote.base-url=")))
                .hasMessageContaining("no coordination server address");
    }

    /**
     * Plain HTTP is refused here even though the production validator would
     * tolerate a loopback address: a file edited by hand on a managed desktop
     * is not where TLS gets switched off.
     */
    @Test
    void plainHttpIsRefusedEvenForLoopback(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> DesktopSettingsFile.read(
                write(dir, "signature.remote.base-url=http://localhost:8080")))
                .hasMessageContaining("must use https://");
    }

    /**
     * Not a general property back door - and in particular not a way to set
     * spring.profiles.active=dev on an installed build.
     */
    @Test
    void anUnknownKeyIsRefused(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> DesktopSettingsFile.read(
                write(dir, "spring.profiles.active=dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not understand")
                .hasMessageContaining("spring.profiles.active");

        assertThatThrownBy(() -> DesktopSettingsFile.read(
                write(dir, "signature.pkcs11.enabled=false")))
                .hasMessageContaining("does not understand");
    }

    @Test
    void theDefaultLocationIsUnderTheUsersOwnProfile() {
        Path file = DesktopSettingsFile.defaultSettingsFile();

        assertThat(file.getFileName().toString()).isEqualTo("settings.properties");
        assertThat(file.getParent().getFileName().toString())
                .isIn("SignFlow", ".signflow");
        // Resolving it must never throw, whatever the environment looks like.
        assertThatCode(DesktopSettingsFile::defaultSettingsFile).doesNotThrowAnyException();
    }

    private static Path write(Path dir, String content) throws Exception {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, content);
        return file;
    }
}

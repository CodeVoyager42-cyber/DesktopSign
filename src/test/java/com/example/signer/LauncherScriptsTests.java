package com.example.signer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Windows launchers against the failure mode they had before
 * the shared runtime discovery was introduced: machine-specific absolute
 * JDK paths ("C:\Users\&lt;name&gt;\.jdks\...") and hardcoded jar version
 * strings ("pdf-signer-desktop-0.1.0-SNAPSHOT.jar") were committed and made
 * the launchers break on every other machine and every version bump.
 *
 * <p>The scripts are read as ISO-8859-1 on purpose: that fixes any single
 * byte mapping, so the tests are independent of how editors/encodings treat
 * the files (the .cmd files are plain ASCII). Only these specific scripts
 * are guarded - they are the ones double-clicked on a developer machine.</p>
 */
class LauncherScriptsTests {

    /** The dev launcher scripts and the packaging script they relate to. */
    private static final List<String> SCRIPTS = List.of(
            "run-app.cmd",
            "run-server.cmd",
            "scripts/dev-runtime.cmd",
            "scripts/register-signflow-protocol.cmd",
            "scripts/package.sh"
    );

    /** The three that resolve Java + jar must use the shared helper. */
    private static final List<String> LAUNCHERS = List.of(
            "run-app.cmd",
            "run-server.cmd",
            "scripts/register-signflow-protocol.cmd"
    );

    /** No absolute per-user Windows paths anywhere (e.g. C:\Users\Mnl\...). */
    private static final Pattern ABSOLUTE_USER_PATH =
            Pattern.compile("[A-Za-z]:\\\\Users\\\\");

    /** The IntelliJ download-JDK location that used to be hardcoded. */
    private static final Pattern DOT_JDKS = Pattern.compile("\\.jdks");

    /** A jar name pinned to a concrete version (pdf-signer-desktop-0.1.0...). */
    private static final Pattern HARDCODED_JAR_VERSION =
            Pattern.compile("pdf-signer-desktop-\\d");

    /** APP_VERSION pinned to a concrete number (package.sh used to do this). */
    private static final Pattern HARDCODED_APP_VERSION =
            Pattern.compile("\\bAPP_VERSION=\\d");

    @Test
    void launchersUseTheSharedRuntimeDiscovery() throws IOException {
        for (String launcher : LAUNCHERS) {
            String text = readScript(launcher);
            assertThat(text).as(launcher).contains("dev-runtime.cmd");
        }
    }

    @Test
    void noScriptHardcodesAMachineSpecificJavaPath() throws IOException {
        for (String script : SCRIPTS) {
            String text = readScript(script);
            assertThat(ABSOLUTE_USER_PATH.matcher(text).find())
                    .as("no absolute C:\\Users\\... path in %s", script)
                    .isFalse();
            assertThat(DOT_JDKS.matcher(text).find())
                    .as("no .jdks path in %s", script)
                    .isFalse();
        }
    }

    @Test
    void noScriptHardcodesTheJarVersion() throws IOException {
        for (String script : SCRIPTS) {
            String text = readScript(script);
            assertThat(HARDCODED_JAR_VERSION.matcher(text).find())
                    .as("no hardcoded jar version in %s - the *.jar wildcard "
                            + "or a derived value keeps the scripts working "
                            + "across version bumps", script)
                    .isFalse();
        }
    }

    @Test
    void packageShDerivesTheVersionFromTheBuiltJar() throws IOException {
        String text = readScript("scripts/package.sh");
        // Picks the newest built jar by glob, not by exact version:
        assertThat(text).contains("target/pdf-signer-desktop-*.jar");
        // jpackage needs a purely numeric --app-version, so the -SNAPSHOT
        // suffix of the pom version is cut into a separate variable:
        assertThat(text).contains("INSTALL_VERSION=");
        assertThat(text).contains("--app-version \"$INSTALL_VERSION\"");
        // APP_VERSION may be assigned from the jar, never to a number:
        assertThat(HARDCODED_APP_VERSION.matcher(text).find())
                .as("no hardcoded APP_VERSION=<number> in package.sh")
                .isFalse();
    }

    @Test
    void devRuntimeDocumentsItsDiscoveryOrder() throws IOException {
        String text = readScript("scripts/dev-runtime.cmd");
        // The documented discovery chain - JAVA_HOME pin, PATH, standard
        // install roots - so future edits keep all three paths covered:
        assertThat(text).contains("JAVA_HOME");
        assertThat(text).contains("where java.exe");
        assertThat(text).contains("Eclipse Adoptium");
        assertThat(text).contains("21");
    }

    private static String readScript(String relative) throws IOException {
        Path root = projectRoot();
        Path file = root.resolve(relative);
        assertThat(file).as("%s must exist", relative).exists();
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }

    /** Walks up from the working directory until the launcher scripts appear. */
    private static Path projectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("run-app.cmd"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("project root (folder containing run-app.cmd)").isNotNull();
        return dir;
    }
}

package com.example.signer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes scripts/dev-runtime.cmd for real (through cmd.exe) against
 * controlled fake JDK trees, and asserts the resolution OUTCOME - the
 * process exit code and WHICH java.exe was chosen - not the script's text.
 *
 * <p>The fake java.exe is a tiny .NET stub compiled once with the .NET
 * Framework batch compiler (csc.exe, present on every supported Windows
 * machine and on GitHub's windows runners): it prints an {@code openjdk
 * version} line whose version it reads from a stub.version.txt next to
 * itself, so one binary can pose as any JDK major version.</p>
 *
 * <p>Coverage (the defects fixed in the review of the 2026 hardening
 * commit):</p>
 * <ul>
 *   <li>D1: a too-old java on PATH must not leave the script returning
 *       failure after the standard install roots supplied a good JDK
 *       (stale RC regression).</li>
 *   <li>D2: a too-old JAVA_HOME pin must fall through to the standard
 *       install roots instead of hard-failing (documented contract).</li>
 *   <li>No JDK anywhere must still fail cleanly with exit code 1.</li>
 *   <li>A good JAVA_HOME pin must keep winning, exactly as before.</li>
 * </ul>
 *
 * <p>The test redirects the script's %ProgramFiles% expansion to a
 * temporary stand-in and rebuilds the child's PATH, so neither the
 * machine's real "C:\Program Files" JDKs nor the inherited PATH can
 * influence the outcome. Windows only: the script under test is a .cmd
 * file.</p>
 */
@EnabledOnOs(OS.WINDOWS)
class DevRuntimeBehaviorTests {

    private static final String CMD = "C:\\Windows\\System32\\cmd.exe";
    private static final String SYSTEM32 = "C:\\Windows\\System32";

    private static Path devRuntime;       // the script under test
    private static Path projRoot;         // empty-project argument for the script
    private static Path csc;              // .NET batch compiler that builds the stub
    private static Path stubExe;          // compiled fake java.exe

    private static Path oldPathDir;       // PATH directory holding an outdated java.exe
    private static Path programFiles;     // %ProgramFiles% stand-in containing a valid JDK 26
    private static Path programFilesEmpty;

    @TempDir
    static Path work;

    @BeforeAll
    static void buildFixtures() throws IOException, InterruptedException {
        csc = findCscCompiler();
        Assumptions.assumeTrue(csc != null,
                "csc.exe (.NET batch compiler) not found; cannot build the fake java stub");

        projectRootIsFoundByWalkingUp();
        devRuntime = projectRoot().resolve("scripts").resolve("dev-runtime.cmd");

        projRoot = work.resolve("proj");
        Files.createDirectories(projRoot.resolve("target"));

        // ---- compile the stub java.exe once --------------------------------
        Path stubSource = work.resolve("StubJava.cs");
        Files.writeString(stubSource, """
                using System;
                using System.IO;
                class StubJava {
                    static int Main() {
                        string dir = AppDomain.CurrentDomain.BaseDirectory;
                        string v = File.ReadAllText(Path.Combine(dir, "stub.version.txt")).Trim();
                        Console.Error.WriteLine("openjdk version \\"" + v + "\\" 1970-01-01");
                        Console.Error.WriteLine("OpenJDK Runtime Environment");
                        return 0;
                    }
                }
                """);
        stubExe = work.resolve("stub-java.exe");
        run(csc.toString(), "/nologo", "/out:" + stubExe, stubSource.toString());

        // ---- fake trees -----------------------------------------------------
        fakeJdk(work, "jdk-17.0.10", "17.0.10");            // JAVA_HOME bait (D2)

        programFiles = work.resolve("pf");
        fakeJdk(programFiles.resolve("Java"), "jdk-26.0.1", "26.0.1");   // valid fallback

        programFilesEmpty = work.resolve("pf-empty");
        Files.createDirectories(programFilesEmpty);

        // PATH directory holding an outdated java.exe (the D1 bait)
        oldPathDir = work.resolve("path-old");
        Files.createDirectories(oldPathDir);
        javaStub(oldPathDir, "17.0.10", stubExe);
    }

    // ------------------------------------------------------------------ tests

    /** D1: outdated java on PATH, then a valid JDK in the fallback roots. */
    @Test
    void outdatedPathJavaFallsBackToInstallRoots() throws Exception {
        Run r = run(null, oldPathDir + ";" + SYSTEM32, programFiles);
        assertThat(r.rc)
                .as("exit code (stale-RC regression: a successful fallback must report success)")
                .isEqualTo(0);
        assertThat(r.javaExe)
                .as("which java.exe the fallback chose (must be the isolated stand-in)")
                .isEqualTo(fake26bin().toString());
        assertThat(r.out).contains("Trying the standard install locations");
        assertThat(r.out).doesNotContain("was not found");
        assertThat(r.out).doesNotContain("cannot find the batch label");
    }

    /** D2: outdated but existing JAVA_HOME pin falls back to the roots. */
    @Test
    void outdatedJavaHomeFallsBackToInstallRoots() throws Exception {
        Path pinned = work.resolve("jdk-17.0.10");
        Run r = run(pinned.toString(), SYSTEM32, programFiles);
        assertThat(r.rc)
                .as("exit code (mode-0 hard-fail regression: must not stop at the bad pin)")
                .isEqualTo(0);
        assertThat(r.javaExe)
                .as("which java.exe the fallback chose (must be the isolated stand-in)")
                .isEqualTo(fake26bin().toString());
        assertThat(r.out).contains("is Java 17");
        assertThat(r.out).doesNotContain("was not found");
        assertThat(r.out).doesNotContain("cannot find the batch label");
    }

    /** A working JAVA_HOME pin must keep winning exactly as before. */
    @Test
    void validJavaHomeStillWins() throws Exception {
        Path home = programFiles.resolve("Java").resolve("jdk-26.0.1");
        Run r = run(home.toString(), SYSTEM32, programFiles);
        assertThat(r.rc).isEqualTo(0);
        assertThat(r.javaExe)
                .as("the pin must win over everything else")
                .isEqualTo(home.resolve("bin").resolve("java.exe").toString());
        assertThat(r.out).doesNotContain("was not found");
        assertThat(r.out).doesNotContain("Trying the standard install locations");
    }

    /** No JDK anywhere: must still fail cleanly (exit 1 + actionable text). */
    @Test
    void noJavaFoundFailsCleanly() throws Exception {
        Run r = run(null, SYSTEM32, programFilesEmpty);
        assertThat(r.rc)
                .as("exit code when nothing usable exists")
                .isEqualTo(1);
        assertThat(r.javaExe).isEmpty();
        assertThat(r.out).contains("was not found");
    }

    // ------------------------------------------------------------------ plumbing

    /** Outcome of one dev-runtime.cmd execution. */
    private static final class Run {
        final int rc;
        final String javaExe;
        final String appJar;
        final String out;

        Run(int rc, String javaExe, String appJar, String out) {
            this.rc = rc;
            this.javaExe = javaExe;
            this.appJar = appJar;
            this.out = out;
        }
    }

    /**
     * Runs dev-runtime.cmd in a child cmd.exe with a fully controlled
     * environment. PATH, %ProgramFiles% and JAVA_HOME are all set INSIDE
     * the runner script (not via ProcessBuilder environment overrides:
     * dynamic Windows variables such as ProgramFiles are not reliably
     * replaceable that way), so the real machine cannot influence the run.
     *
     * @param javaHome      value written to JAVA_HOME, or {@code null} for unset
     * @param path          the complete PATH the child will use
     * @param programFiles  the directory the script's %ProgramFiles% expands to
     */
    private Run run(String javaHome, String path, Path programFiles) throws Exception {
        Path runner = work.resolve("run-" + System.nanoTime() + ".cmd");
        List<String> lines = new ArrayList<>(List.of(
                "@echo off",
                "set \"PATH=" + path + "\"",
                "set \"ProgramFiles=" + programFiles + "\""));
        if (javaHome != null) {
            lines.add("set \"JAVA_HOME=" + javaHome + "\"");
        } else {
            // Actively CLEAR the inherited value. Merely not setting it lets
            // the child cmd.exe inherit JAVA_HOME from the Maven process -
            // on a CI runner that is the runner's real JDK (C:\hostedtoolcache\...),
            // which then wins the script's #1 priority slot and defeats the
            // scenario ("outdatedPathJavaFallsBackToInstallRoots" chose the
            // runner's JDK; "noJavaFoundFailsCleanly" returned success).
            // "set JAVA_HOME=" is cmd's way to unset a variable.
            lines.add("set \"JAVA_HOME=\"");
        }
        lines.addAll(List.of(
                "call \"" + devRuntime + "\" \"" + projRoot + "\"",
                "echo RC_RESULT=%ERRORLEVEL%",
                "echo JAVA_EXE_RESULT=%JAVA_EXE%",
                "echo APP_JAR_RESULT=%APP_JAR%",
                "exit /b %ERRORLEVEL%",   // relay the dev-runtime code as the process exit
                ""));
        Files.writeString(runner, String.join("\r\n", lines), StandardCharsets.ISO_8859_1);

        ProcessBuilder pb = new ProcessBuilder(CMD, "/d", "/c", runner.toString());
        pb.redirectErrorStream(true);

        Process p = pb.start();
        String out;
        try (var in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("dev-runtime.cmd did not finish within 60s");
        }

        String javaExe = "", appJar = "", rcPrinted = "";
        for (String line : out.split("\r?\n")) {
            line = line.trim();
            if (line.startsWith("RC_RESULT=")) {
                rcPrinted = line.substring("RC_RESULT=".length()).trim();
            } else if (line.startsWith("JAVA_EXE_RESULT=")) {
                javaExe = line.substring("JAVA_EXE_RESULT=".length()).trim();
            } else if (line.startsWith("APP_JAR_RESULT=")) {
                appJar = line.substring("APP_JAR_RESULT=".length()).trim();
            }
        }
        // cross-check the two exit-code channels (call's errorlevel vs process exit)
        if (!rcPrinted.isBlank()) {
            int printed = Integer.parseInt(rcPrinted);
            assertThat(printed).as("dev-runtime exit code via runner echo").isEqualTo(p.exitValue());
        }
        assertThat(appJar)
                .as("APP_JAR (the fixture project has no built jar)")
                .isEmpty();

        return new Run(p.exitValue(), javaExe, appJar, out);
    }

    private static void run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("setup command did not finish: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            throw new AssertionError("setup command failed: " + String.join(" ", cmd));
        }
    }

    /** Builds a fake JDK tree: name\bin\java.exe (stub) + stub.version.txt. */
    private static void fakeJdk(Path parent, String name, String version) throws IOException {
        Path bin = parent.resolve(name).resolve("bin");
        Files.createDirectories(bin);
        javaStub(bin, version, stubExe);
    }

    /** The java.exe the valid fallback tree provides under the stand-in %ProgramFiles%. */
    private static Path fake26bin() {
        return programFiles.resolve("Java").resolve("jdk-26.0.1").resolve("bin").resolve("java.exe");
    }

    private static void javaStub(Path bin, String version, Path fromExe) throws IOException {
        Files.copy(fromExe, bin.resolve("java.exe"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(bin.resolve("stub.version.txt"), version);
    }

    /** The .NET Framework batch compiler, newest version first. */
    private static Path findCscCompiler() {
        for (String base : new String[] {
                "C:\\Windows\\Microsoft.NET\\Framework64", "C:\\Windows\\Microsoft.NET\\Framework"}) {
            Path bp = Path.of(base);
            if (!Files.isDirectory(bp)) continue;
            List<Path> versions = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(bp, "v*")) {
                for (Path v : ds) {
                    versions.add(v);
                }
            } catch (IOException ignored) {
                // try the next base
            }
            versions.sort(Comparator.comparing((Path v) -> v.getFileName().toString()).reversed());
            for (Path v : versions) {
                Path c = v.resolve("csc.exe");
                if (Files.isRegularFile(c)) {
                    return c;
                }
            }
        }
        return null;
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

    private static void projectRootIsFoundByWalkingUp() {
        // sanity that the walk-up works before fixtures depend on it
        org.junit.jupiter.api.Assertions.assertNotNull(projectRoot());
    }
}

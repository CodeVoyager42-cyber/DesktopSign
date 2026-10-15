package com.example.signer.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Registers the {@code signflow://} URL protocol for the current Windows
 * user (HKCU, no admin rights) so that an emailed invitation link opens this
 * installation of SignFlow directly — see {@link InvitationLinks} and
 * {@code docs/remote-workflow.md} §9.
 *
 * <p>Only called for packaged installs ({@code -Dsignflow.packaged=true},
 * set by {@code package.sh}): at that point {@code ProcessHandle.current()}
 * reports the real native launcher executable (jpackage produces an actual
 * OS-level exe, not a batch wrapper), so the registration self-heals after
 * every reinstall, update or relocation — no install-time script, nothing
 * coworkers run by hand. Development launches ({@code mvnw javafx:run},
 * {@code run-app.cmd}) are skipped here; use
 * {@code register-signflow-protocol.cmd} to test deep links from source.
 *
 * <p>The registry values are written via a generated {@code .reg} file
 * imported with {@code reg import}, not {@code reg add /d "..."}: the
 * {@code shell\open\command} value is two separately quoted tokens
 * ({@code "<exe>" "%1"}), and {@link ProcessBuilder} on Windows cannot
 * reliably rebuild that into a command line {@code reg.exe} accepts (
 * verified empirically — it fails with "Invalid syntax", a known
 * child-process argument-quoting gap, not anything reg.exe-specific). A
 * {@code .reg} file carries the value as plain escaped text instead, so no
 * argv-quoting layer is involved.
 */
final class ProtocolRegistration {

    private static final Logger log = LoggerFactory.getLogger(ProtocolRegistration.class);

    private static final String KEY = "HKCU\\Software\\Classes\\signflow";

    private ProtocolRegistration() {
    }

    /** Best-effort; never throws — a failed registration must not block startup. */
    static void ensureRegisteredForPackagedInstall() {
        if (!"true".equals(System.getProperty("signflow.packaged"))) {
            return;
        }
        if (!isWindows()) {
            return;
        }
        try {
            String exePath = ProcessHandle.current().info().command().orElse(null);
            if (exePath == null) {
                log.warn("Could not determine this installation's executable path;"
                        + " skipping signflow:// registration");
                return;
            }
            if (isAlreadyRegisteredFor(exePath)) {
                return;
            }
            importRegFile(exePath);
            log.info("Registered signflow:// to open {}", exePath);
        } catch (Exception e) {
            // Same HKCU key a developer could have set by hand with
            // register-signflow-protocol.cmd — never fight the user over it.
            log.warn("Could not register signflow:// protocol: {}", e.toString());
        }
    }

    private static boolean isAlreadyRegisteredFor(String exePath) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("reg", "query", KEY + "\\shell\\open\\command")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return p.waitFor() == 0 && out.contains(exePath);
    }

    private static void importRegFile(String exePath) throws IOException, InterruptedException {
        Path regFile = Files.createTempFile("signflow-protocol", ".reg");
        try {
            Files.writeString(regFile, regFileContent(exePath), StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("reg", "import", regFile.toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IOException("reg import failed: " + out.trim());
            }
        } finally {
            Files.deleteIfExists(regFile);
        }
    }

    /** {@code .reg} file escaping: backslashes doubled, then the whole value quoted. */
    private static String regFileContent(String exePath) {
        String esc = exePath.replace("\\", "\\\\").replace("\"", "\\\"");
        return "Windows Registry Editor Version 5.00\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Classes\\signflow]\r\n"
                + "@=\"url:SignFlow Invitation\"\r\n"
                + "\"URL Protocol\"=\"\"\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Classes\\signflow\\DefaultIcon]\r\n"
                + "@=\"\\\"" + esc + "\\\",0\"\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Classes\\signflow\\shell\\open\\command]\r\n"
                + "@=\"\\\"" + esc + "\\\" \\\"%1\\\"\"\r\n";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}

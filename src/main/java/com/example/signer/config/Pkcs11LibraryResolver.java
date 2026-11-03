package com.example.signer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves the PKCS#11 driver of the signer's hardware token.
 *
 * <p>End users never touch a terminal: when {@code PKCS11_LIBRARY} is not
 * set, SignFlow scans the standard Windows driver directory for the known
 * Gemalto / eBarid Sign middleware libraries ({@code eTPKCS11.dll},
 * {@code gclib.dll}) and uses the first one it finds. Only an explicit
 * configuration wins over the auto-detection.
 *
 * <p>Both readers of {@code signature.pkcs11.library} — the startup safety
 * check and the signing-token provider — go through this resolver, so they
 * always agree on which driver the application would use.
 */
public final class Pkcs11LibraryResolver {

    private static final Logger log = LoggerFactory.getLogger(Pkcs11LibraryResolver.class);

    /** Gemalto / eBarid Sign PKCS#11 middleware, as installed in System32. */
    public static final String ETPKCS11 = "eTPKCS11.dll";
    public static final String GCLIB = "gclib.dll";

    /** The standard Windows driver directory (the only default scan root). */
    public static final String SYSTEM32 = "C:\\Windows\\System32";

    /** The known driver file names, in scan order. */
    public static final List<String> KNOWN_DRIVERS = List.of(ETPKCS11, GCLIB);

    private Pkcs11LibraryResolver() {
    }

    /**
     * Returns the driver to use: the explicitly configured {@code library}
     * when it is set, otherwise the first known driver found in
     * {@code scanDirs} (in the given order). Returns an empty string when
     * nothing applies — callers decide what that means for them.
     *
     * @param configuredLibrary the value of {@code signature.pkcs11.library}
     *                          (may be null or blank)
     * @param scanDirs          directories to scan when nothing is configured
     */
    public static String resolve(String configuredLibrary, List<String> scanDirs) {
        String configured = configuredLibrary == null ? "" : configuredLibrary.trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        for (String dir : scanDirs) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (String candidate : KNOWN_DRIVERS) {
                Path path = Path.of(dir.trim(), candidate);
                if (Files.isRegularFile(path)) {
                    log.info("No PKCS11_LIBRARY is configured; using the auto-detected"
                            + " Gemalto/eBarid PKCS#11 driver: {}", path);
                    return path.toString();
                }
            }
        }
        return "";
    }
}

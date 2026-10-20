package com.example.signer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fail-fast guard: outside the {@code dev} profile, SignFlow refuses to
 * start with any development security setting.
 *
 * <p>Created during context startup in every SignFlow context (desktop and
 * coordination server). When a rule is violated the context fails with ONE
 * message listing every problem, so the operator can fix the configuration
 * in a single pass. There is no "warn and continue" for these rules: each
 * of them would otherwise silently weaken signatures, authentication or
 * transport security.
 *
 * <ul>
 *   <li><b>Desktop</b> (profile {@code desktop}): a real PKCS#11 token must
 *       be configured — no mock signing; the coordination server URL must
 *       be {@code https} (plain HTTP is accepted only for this machine's
 *       loopback address).</li>
 *   <li><b>Coordination server</b> (profile {@code server}): TLS is required
 *       (Spring Boot {@code server.ssl.enabled} or an explicit
 *       {@code signature.remote-server.tls-offloaded=true} for a TLS reverse
 *       proxy); plaintext passwords are refused; in database mode the
 *       emailed invitation links must be {@code https} and email must be
 *       really delivered (SMTP), not printed to a console.</li>
 *   <li><b>Packaged builds</b> ({@code -Dsignflow.packaged=true} from
 *       package.sh, or jpackage's {@code jpackage.app-version})
 *       can never run the {@code dev} profile.</li>
 * </ul>
 *
 * <p>The JWT secret rule lives in {@code JwtService}, which fails on its own
 * when the server starts without a secret.
 */
@Component
public class ProductionSafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyValidator.class);

    public ProductionSafetyValidator(Environment environment) {
        validate(environment);
    }

    /** Validates {@code environment}; throws {@link IllegalStateException} on violations. */
    public static void validate(Environment environment) {
        boolean dev = environment.acceptsProfiles(Profiles.of("dev"));
        boolean server = environment.acceptsProfiles(Profiles.of("server"));
        boolean desktop = environment.acceptsProfiles(Profiles.of("desktop"));

        if (dev) {
            if (System.getProperty("jpackage.app-version") != null
                    || Boolean.getBoolean("signflow.packaged")) {
                throw new IllegalStateException("The 'dev' profile cannot be used by an"
                        + " installed SignFlow build: it enables the development mock"
                        + " token, default accounts and plain HTTP.");
            }
            log.warn("=================================================================");
            log.warn(" SignFlow is running in DEVELOPMENT mode (profile 'dev'):");
            log.warn(" mock signing token, development accounts, plain HTTP allowed.");
            log.warn(" NEVER use this configuration in production.");
            log.warn("=================================================================");
            return;
        }

        List<String> problems = new ArrayList<>();
        if (desktop) {
            checkDesktop(environment, problems);
        }
        if (server) {
            checkServer(environment, problems);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("SignFlow refuses to start with an unsafe"
                    + " production configuration:\n - " + String.join("\n - ", problems)
                    + "\nFor local development only, start with the 'dev' profile"
                    + " (--spring.profiles.active=dev).");
        }
        log.info("Production safety checks passed ({}{})",
                desktop ? "desktop" : "", server ? (desktop ? ", server" : "server") : "");
    }

    private static void checkDesktop(Environment env, List<String> problems) {
        boolean pkcs11 = Boolean.parseBoolean(env.getProperty("signature.pkcs11.enabled", "false"));
        String library = env.getProperty("signature.pkcs11.library", "").trim();
        if (!pkcs11) {
            problems.add("No signing token is configured: set SIGNATURE_PKCS11_ENABLED=true"
                    + " and PKCS11_LIBRARY to the PKCS#11 library of your smartcard/USB"
                    + " token (the development mock token is never used in production).");
        } else if (library.isEmpty()) {
            problems.add("SIGNATURE_PKCS11_ENABLED=true but PKCS11_LIBRARY is not set.");
        } else if ((library.contains("/") || library.contains("\\"))
                && !Files.exists(Path.of(library))) {
            problems.add("The PKCS#11 library does not exist: " + library);
        }
        String remote = env.getProperty("signature.remote.base-url", "");
        if (!remote.isBlank() && !isHttpsOrLoopback(remote)) {
            problems.add("The coordination server URL must use https:// (got " + remote
                    + "). Passwords, tokens and documents never travel over plain HTTP.");
        }
    }

    private static void checkServer(Environment env, List<String> problems) {
        // Spring Boot applies TLS when a key store / SSL bundle is configured
        // and server.ssl.enabled is not explicitly false.
        boolean sslConfigured = !env.getProperty("server.ssl.key-store", "").isBlank()
                || !env.getProperty("server.ssl.bundle", "").isBlank()
                || !env.getProperty("server.ssl.certificate", "").isBlank();
        boolean ssl = sslConfigured
                && Boolean.parseBoolean(env.getProperty("server.ssl.enabled", "true"));
        boolean offloaded = Boolean.parseBoolean(
                env.getProperty("signature.remote-server.tls-offloaded", "false"));
        if (!ssl && !offloaded) {
            problems.add("TLS is not configured: enable server.ssl.* (keystore) or, behind"
                    + " a TLS-terminating reverse proxy, set SIGNER_TLS_OFFLOADED=true.");
        }
        // Behind a proxy every request arrives from the proxy's address. The
        // sign-in limit is per client address, so without the forwarded
        // address all users would share one limit (security Phase 2, L2).
        String forwarded = env.getProperty("server.forward-headers-strategy", "none")
                .trim().toLowerCase(Locale.ROOT);
        if (offloaded && !ssl && !("native".equals(forwarded) || "framework".equals(forwarded))) {
            problems.add("SIGNER_TLS_OFFLOADED=true but forwarded client addresses are not"
                    + " used: set SIGNER_FORWARD_HEADERS_STRATEGY=native (the proxy must send"
                    + " X-Forwarded-For and run on a private address), otherwise every user"
                    + " shares one sign-in limit.");
        }
        if (Boolean.parseBoolean(env.getProperty(
                "signature.remote-server.allow-plaintext-passwords", "false"))) {
            problems.add("signature.remote-server.allow-plaintext-passwords=true is a"
                    + " development setting.");
        }
        if (Boolean.parseBoolean(env.getProperty(
                "signature.remote-server.accept-development-identities", "false"))) {
            problems.add("signature.remote-server.accept-development-identities=true lets"
                    + " development mock-token certificates sign workflows.");
        }
        boolean database = Boolean.parseBoolean(
                env.getProperty("signature.remote-server.database.enabled", "false"));
        if (database) {
            String publicBase = env.getProperty("signature.remote-server.invitation.public-base-url", "");
            String appBase = env.getProperty("app.base-url", "");
            String linkBase = !publicBase.isBlank() ? publicBase : appBase;
            if (!linkBase.toLowerCase(Locale.ROOT).startsWith("https://")) {
                problems.add("Invitation links must be https: set SIGNER_PUBLIC_BASE_URL to"
                        + " the public https:// address of this server (got '"
                        + linkBase + "').");
            }
            if (!Boolean.parseBoolean(env.getProperty(
                    "signature.remote-server.invitation.guest-verification", "true"))) {
                problems.add("SIGNER_GUEST_VERIFICATION=false lets an invitation link alone"
                        + " open a guest signing session (the sender, or anyone the e-mail"
                        + " reaches, could sign in the invitee's place).");
            }
            if (Boolean.parseBoolean(env.getProperty(
                    "signature.remote-server.desktop.accept-unknown-version", "false"))) {
                problems.add("SIGNER_DESKTOP_ACCEPT_UNKNOWN_VERSION=true lets desktop builds"
                        + " that do not report a version open launch links (development"
                        + " only).");
            }
            String mode = env.getProperty("signature.remote-server.email.mode", "console");
            if (!"smtp".equalsIgnoreCase(mode.trim())) {
                problems.add("SIGNER_EMAIL_MODE must be smtp: the console mailbox is a"
                        + " development tool and never delivers invitations.");
            }
        }
    }

    /** Whether {@code url} is https, or plain http to this machine only. */
    public static boolean isHttpsOrLoopback(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ("https".equals(scheme)) {
                return true;
            }
            return "http".equals(scheme) && isLoopbackHost(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Literal loopback names only (no DNS lookup). */
    public static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.startsWith("127.")
                || h.equals("[::1]") || h.equals("::1");
    }
}

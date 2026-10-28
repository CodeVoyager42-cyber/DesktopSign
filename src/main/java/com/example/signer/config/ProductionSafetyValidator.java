package com.example.signer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 *       can never run the {@code dev} profile — unless the operator
 *       explicitly opts in with the environment variable
 *       {@code ALLOW_DEV_PROFILE=true}, e.g. to test an installed build
 *       against the development server on the same machine.</li>
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
            boolean packaged = System.getProperty("jpackage.app-version") != null
                    || Boolean.getBoolean("signflow.packaged");
            // Escape hatch for operators testing an INSTALLED build (e.g.
            // the MSI on this machine) against the development mock server.
            // Read through the Environment so BOTH the process environment
            // variable ALLOW_DEV_PROFILE=true and -DALLOW_DEV_PROFILE=true
            // are honoured; the value must be exactly "true" (whitespace and
            // other values keep the packaged build blocked).
            boolean override = Boolean.parseBoolean(
                    environment.getProperty("ALLOW_DEV_PROFILE", "false"));
            if (packaged && !override) {
                throw new IllegalStateException("The 'dev' profile cannot be used by an"
                        + " installed SignFlow build: it enables the development mock"
                        + " token, default accounts and plain HTTP. To test an installed"
                        + " build deliberately, set the environment variable"
                        + " ALLOW_DEV_PROFILE=true.");
            }
            if (packaged) {
                log.warn("=================================================================");
                log.warn(" ALLOW_DEV_PROFILE=true: a PACKAGED build is running in"
                        + " DEVELOPMENT mode (profile 'dev').");
                log.warn(" Development accounts and the mock signing token are active"
                        + " for this run.");
                log.warn(" NEVER use this configuration in production.");
                log.warn("=================================================================");
                return;
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
            // The website chain is active exactly when the database is, since
            // its session store is PostgreSQL (security Phase C).
            checkWebSession(env, problems);
        }
    }

    /** Longest idle window a production website session may have. */
    private static final Duration MAX_IDLE = Duration.ofHours(12);
    /** Longest total lifetime a production website session may have. */
    private static final Duration MAX_ABSOLUTE = Duration.ofHours(24);

    /**
     * Website session settings (security Phase C). The {@code dev} profile
     * weakens the cookie so the site works over plain {@code http://localhost};
     * production must not run with those values.
     */
    private static void checkWebSession(Environment env, List<String> problems) {
        String cookie = env.getProperty(
                "signature.remote-server.web.session.cookie-name", "__Host-SF_SESSION").trim();
        if (!cookie.startsWith("__Host-")) {
            problems.add("The website session cookie must keep the __Host- prefix (got '"
                    + cookie + "'). With it the browser itself enforces Secure, Path=/ and"
                    + " no Domain, so neither a sibling subdomain nor a plain-HTTP page can"
                    + " set the session cookie. SIGNER_WEB_SESSION_COOKIE is a development"
                    + " setting.");
        }
        if (!Boolean.parseBoolean(env.getProperty(
                "signature.remote-server.web.session.secure-cookie", "true"))) {
            problems.add("SIGNER_WEB_SESSION_SECURE=false would send the website session"
                    + " cookie AND the CSRF token cookie over plain HTTP, where any network"
                    + " observer can take both — which defeats the double-submit check as"
                    + " well as the session. It is a development setting.");
        }
        Duration idle = duration(env, "idle-timeout", "PT30M", problems);
        Duration absolute = duration(env, "absolute-timeout", "PT8H", problems);
        if (idle != null && (idle.isZero() || idle.isNegative() || idle.compareTo(MAX_IDLE) > 0)) {
            problems.add("SIGNER_WEB_SESSION_IDLE must be between 0 and " + MAX_IDLE
                    + " (got " + idle + "): a website session that never times out is a"
                    + " permanent credential in a browser.");
        }
        if (absolute != null && (absolute.isZero() || absolute.isNegative()
                || absolute.compareTo(MAX_ABSOLUTE) > 0)) {
            problems.add("SIGNER_WEB_SESSION_ABSOLUTE must be between 0 and " + MAX_ABSOLUTE
                    + " (got " + absolute + ").");
        }
        Duration anonymous = duration(env, "anonymous-idle-timeout", "PT5M", problems);
        if (anonymous != null && idle != null
                && (anonymous.isZero() || anonymous.isNegative()
                        || anonymous.compareTo(idle) > 0)) {
            problems.add("SIGNER_WEB_SESSION_ANON_IDLE must be positive and no longer than"
                    + " SIGNER_WEB_SESSION_IDLE (got " + anonymous + " against " + idle
                    + "): a visitor who never signs in must not hold a session longer than"
                    + " one who did.");
        }
        if (idle != null && absolute != null && absolute.compareTo(idle) < 0) {
            problems.add("SIGNER_WEB_SESSION_ABSOLUTE (" + absolute + ") is shorter than"
                    + " SIGNER_WEB_SESSION_IDLE (" + idle + "), so the idle window could"
                    + " never be reached. One of them is wrong.");
        }
    }

    /** Reads an ISO-8601 duration property, reporting an unparseable value. */
    private static Duration duration(Environment env, String key, String fallback,
                                     List<String> problems) {
        String raw = env.getProperty(
                "signature.remote-server.web.session." + key, fallback).trim();
        try {
            return Duration.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            problems.add("signature.remote-server.web.session." + key + " is not an"
                    + " ISO-8601 duration (got '" + raw + "', expected e.g. PT30M).");
            return null;
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

package com.example.signer.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0.1/P0.3/P0.9: outside the {@code dev} profile, SignFlow refuses to start
 * with development security settings instead of silently using them.
 */
class ProductionSafetyValidatorTests {

    @AfterEach
    void clearPackagedFlag() {
        System.clearProperty("signflow.packaged");
        System.clearProperty("signflow.local-build");
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void productionDesktopWithoutAPkcs11TokenFailsFast() {
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "false");
        env.setProperty("signature.remote.base-url", "https://signflow.example.com");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No signing token is configured");
    }

    /** The end-user path: no PKCS11_LIBRARY, but the Gemalto/eBarid driver is installed. */
    @Test
    void productionDesktopPassesWhenTheGemaltoDriverIsAutoDetected(@TempDir Path system32) throws Exception {
        Files.writeString(system32.resolve("eTPKCS11.dll"), "fake driver");
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "true");
        env.setProperty("signature.remote.base-url", "https://signflow.example.com");
        env.setProperty("signature.pkcs11.auto-detect-dirs", system32.toString());
        assertThatCode(() -> ProductionSafetyValidator.validate(env)).doesNotThrowAnyException();
    }

    /** Same, but the driver directory has nothing: the problem says what to do. */
    @Test
    void productionDesktopWithoutTheKnownDriversExplainsTheAutoDetection(@TempDir Path system32) {
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "true");
        env.setProperty("signature.remote.base-url", "https://signflow.example.com");
        env.setProperty("signature.pkcs11.auto-detect-dirs", system32.toString());
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("none was auto-detected")
                .hasMessageContaining("eTPKCS11.dll");
    }

    /** A configured but nonexistent library path keeps its explicit problem. */
    @Test
    void productionDesktopWithANonexistentConfiguredLibraryFails() {
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "true");
        env.setProperty("signature.pkcs11.library", "C:\\no\\such\\driver.dll");
        env.setProperty("signature.remote.base-url", "https://signflow.example.com");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("does not exist");
    }

    @Test
    void productionDesktopRefusesPlainHttpToARemoteServer() {
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "true");
        env.setProperty("signature.pkcs11.library", "vendor-pkcs11.dll");
        env.setProperty("signature.remote.base-url", "http://192.168.1.20:8080");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("https://");
    }

    @Test
    void productionDesktopWithTokenAndHttpsStarts() {
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "true");
        env.setProperty("signature.pkcs11.library", "vendor-pkcs11.dll");
        env.setProperty("signature.remote.base-url", "https://signflow.example.com");
        assertThatCode(() -> ProductionSafetyValidator.validate(env)).doesNotThrowAnyException();
    }

    @Test
    void productionServerRejectsEveryDevelopmentSetting() {
        MockEnvironment env = env("server");
        env.setProperty("signature.remote-server.allow-plaintext-passwords", "true");
        env.setProperty("signature.remote-server.accept-development-identities", "true");
        env.setProperty("signature.remote-server.database.enabled", "true");
        env.setProperty("app.base-url", "http://localhost:8080");
        env.setProperty("signature.remote-server.email.mode", "console");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLS is not configured")
                .hasMessageContaining("allow-plaintext-passwords")
                .hasMessageContaining("accept-development-identities")
                .hasMessageContaining("Invitation links must be https")
                .hasMessageContaining("SIGNER_EMAIL_MODE must be smtp");
    }

    @Test
    void productionServerWithTlsAndSafeSettingsStarts() {
        MockEnvironment env = env("server");
        env.setProperty("server.ssl.key-store", "classpath:server.p12");
        env.setProperty("signature.remote-server.database.enabled", "true");
        env.setProperty("signature.remote-server.invitation.public-base-url",
                "https://signflow.example.com");
        env.setProperty("signature.remote-server.email.mode", "smtp");
        assertThatCode(() -> ProductionSafetyValidator.validate(env)).doesNotThrowAnyException();

        MockEnvironment offloaded = env("server");
        offloaded.setProperty("signature.remote-server.tls-offloaded", "true");
        offloaded.setProperty("server.forward-headers-strategy", "native");
        assertThatCode(() -> ProductionSafetyValidator.validate(offloaded))
                .doesNotThrowAnyException();
    }

    @Test
    void behindATlsProxyTheForwardedClientAddressIsRequired() {
        MockEnvironment offloaded = env("server");
        offloaded.setProperty("signature.remote-server.tls-offloaded", "true");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(offloaded))
                .hasMessageContaining("SIGNER_FORWARD_HEADERS_STRATEGY=native");
        offloaded.setProperty("server.forward-headers-strategy", "framework");
        assertThatCode(() -> ProductionSafetyValidator.validate(offloaded))
                .doesNotThrowAnyException();
    }

    @Test
    void devProfileIsAllowedButNeverInAPackagedBuild() {
        assertThatCode(() -> ProductionSafetyValidator.validate(env("desktop", "dev")))
                .doesNotThrowAnyException();
        System.setProperty("signflow.packaged", "true");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env("desktop", "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be used by an installed SignFlow build")
                // The refusal has to say what to do instead, or the next
                // person looking for a way through invents one.
                .hasMessageContaining("run SignFlow unpackaged");
    }

    /** jpackage sets this one itself, so it blocks an installed build too. */
    @Test
    void theJpackageVersionPropertyAlsoMarksABuildAsPackaged() {
        System.setProperty("jpackage.app-version", "0.2.1");
        try {
            assertThatThrownBy(() -> ProductionSafetyValidator.validate(env("desktop", "dev")))
                    .hasMessageContaining("cannot be used by an installed SignFlow build");
        } finally {
            System.clearProperty("jpackage.app-version");
        }
    }

    /**
     * There is NO way to run the dev profile in a packaged build. An earlier
     * revision honoured an ALLOW_DEV_PROFILE=true opt-in; it shipped in the
     * released v0.2.1 and is gone again. This pins that: the former opt-in
     * is inert, whether it arrives as an environment property or as a system
     * property, and whatever it is set to.
     */
    @Test
    void noOverrideLetsAPackagedBuildRunDevMode() {
        System.setProperty("signflow.packaged", "true");
        try {
            for (String value : new String[] {"true", "TRUE", "True", "1", "yes"}) {
                MockEnvironment env = env("desktop", "dev");
                env.setProperty("ALLOW_DEV_PROFILE", value);
                assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                        .as("ALLOW_DEV_PROFILE=<%s>", value)
                        .hasMessageContaining("installed SignFlow build");
            }

            // The real Environment chain sees system properties, exactly as a
            // packaged build's startup would: -DALLOW_DEV_PROFILE=true is no
            // way through either.
            System.setProperty("ALLOW_DEV_PROFILE", "true");
            StandardEnvironment sysEnv = new StandardEnvironment();
            sysEnv.setActiveProfiles("desktop", "dev");
            assertThatThrownBy(() -> ProductionSafetyValidator.validate(sysEnv))
                    .hasMessageContaining("installed SignFlow build");
        } finally {
            System.clearProperty("ALLOW_DEV_PROFILE");
        }
    }

    /** Even the real Environment chain stays blocked. */
    @Test
    void aRealEnvironmentStaysBlockedInAPackagedBuild() {
        System.setProperty("signflow.packaged", "true");
        StandardEnvironment sysEnv = new StandardEnvironment();
        sysEnv.setActiveProfiles("desktop", "dev");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(sysEnv))
                .hasMessageContaining("installed SignFlow build");
    }

    /* ---- the address a packaged build was built for --------------------- */

    /**
     * An installed build has to point at a server other people can reach.
     * scripts/package.sh refuses to produce one without an address; this is
     * the same rule at the other end, for an app-image assembled by some
     * other route.
     */
    @Test
    void aPackagedBuildWithNoServerAddressRefusesToStart() {
        System.setProperty("signflow.packaged", "true");
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "true");
        env.setProperty("signature.pkcs11.library", "eTPKCS11.dll");

        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("packaged SignFlow build with no coordination"
                        + " server address")
                .hasMessageContaining("SIGNFLOW_SERVER_URL");
    }

    /** The default localhost address is exactly the useless installer. */
    @Test
    void aPackagedBuildPointedAtThisMachineRefusesToStart() {
        System.setProperty("signflow.packaged", "true");
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "true");
        env.setProperty("signature.pkcs11.library", "eTPKCS11.dll");
        env.setProperty("signature.remote.base-url", "http://localhost:8080");

        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("which is this machine")
                .hasMessageContaining("cannot reach it");
    }

    /**
     * scripts/package.sh --local marks a build that was deliberately pointed
     * here. It excuses the loopback address and nothing else: the dev profile
     * stays refused (see noOverrideLetsAPackagedBuildRunDevMode).
     */
    @Test
    void aLocalBuildMayPointAtThisMachine() {
        System.setProperty("signflow.packaged", "true");
        System.setProperty("signflow.local-build", "true");
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "true");
        env.setProperty("signature.pkcs11.library", "eTPKCS11.dll");
        env.setProperty("signature.remote.base-url", "http://localhost:8080");

        assertThatCode(() -> ProductionSafetyValidator.validate(env))
                .doesNotThrowAnyException();
    }

    @Test
    void aPackagedBuildWithARealHttpsServerStarts() {
        System.setProperty("signflow.packaged", "true");
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "true");
        env.setProperty("signature.pkcs11.library", "eTPKCS11.dll");
        env.setProperty("signature.remote.base-url", "https://signflow.example.com");

        assertThatCode(() -> ProductionSafetyValidator.validate(env))
                .doesNotThrowAnyException();
    }

    /** Running from source is not held to the packaged-build rule. */
    @Test
    void anUnpackagedBuildMayPointAtThisMachine() {
        MockEnvironment env = env("desktop");
        env.setProperty("signature.pkcs11.enabled", "true");
        env.setProperty("signature.pkcs11.library", "eTPKCS11.dll");
        env.setProperty("signature.remote.base-url", "http://localhost:8080");

        assertThatCode(() -> ProductionSafetyValidator.validate(env))
                .doesNotThrowAnyException();
    }

    /**
     * The shape of the address is judged by the same rules the build applies
     * (ServerUrlRules), so a path or a trailing slash is caught here too
     * rather than producing a wrong URL at the far end.
     */
    @Test
    void anAddressWithAPathOrATrailingSlashIsRefused() {
        MockEnvironment withPath = env("desktop");
        withPath.setProperty("signature.pkcs11.enabled", "true");
        withPath.setProperty("signature.pkcs11.library", "eTPKCS11.dll");
        withPath.setProperty("signature.remote.base-url", "https://signflow.example.com/api");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(withPath))
                .hasMessageContaining("coordination server URL is unusable")
                .hasMessageContaining("no path");

        MockEnvironment trailing = env("desktop");
        trailing.setProperty("signature.pkcs11.enabled", "true");
        trailing.setProperty("signature.pkcs11.library", "eTPKCS11.dll");
        trailing.setProperty("signature.remote.base-url", "https://signflow.example.com/");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(trailing))
                .hasMessageContaining("trailing slash");
    }

    @Test
    void loopbackIsTheOnlyPlainHttpException() {
        assertThat(ProductionSafetyValidator.isHttpsOrLoopback("https://a.example")).isTrue();
        assertThat(ProductionSafetyValidator.isHttpsOrLoopback("http://localhost:8080")).isTrue();
        assertThat(ProductionSafetyValidator.isHttpsOrLoopback("http://127.0.0.1:8080")).isTrue();
        assertThat(ProductionSafetyValidator.isHttpsOrLoopback("http://localhost.evil.com")).isFalse();
        assertThat(ProductionSafetyValidator.isHttpsOrLoopback("http://10.0.0.5")).isFalse();
        assertThat(ProductionSafetyValidator.isHttpsOrLoopback("ftp://localhost")).isFalse();
    }

    @Test
    void guestVerificationCannotBeSwitchedOffInProduction() {
        MockEnvironment env = env("server");
        env.setProperty("server.ssl.key-store", "classpath:server.p12");
        env.setProperty("signature.remote-server.database.enabled", "true");
        env.setProperty("signature.remote-server.invitation.public-base-url",
                "https://signflow.example.com");
        env.setProperty("signature.remote-server.email.mode", "smtp");
        env.setProperty("signature.remote-server.invitation.guest-verification", "false");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("SIGNER_GUEST_VERIFICATION=false");
    }

    @Test
    void productionServerRefusesDesktopsOfUnknownVersion() {
        MockEnvironment env = env("server");
        env.setProperty("server.ssl.key-store", "classpath:server.p12");
        env.setProperty("signature.remote-server.database.enabled", "true");
        env.setProperty("signature.remote-server.invitation.public-base-url",
                "https://signflow.example.com");
        env.setProperty("signature.remote-server.email.mode", "smtp");
        env.setProperty("signature.remote-server.desktop.accept-unknown-version", "true");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("SIGNER_DESKTOP_ACCEPT_UNKNOWN_VERSION=true");
    }

    /* ------------- website sessions (security Phase C) ----------------- */

    /** A production server in database mode, safe apart from what a test changes. */
    private static MockEnvironment productionWebServer() {
        MockEnvironment env = env("server");
        env.setProperty("server.ssl.key-store", "classpath:server.p12");
        env.setProperty("signature.remote-server.database.enabled", "true");
        env.setProperty("signature.remote-server.invitation.public-base-url",
                "https://signflow.example.com");
        env.setProperty("signature.remote-server.email.mode", "smtp");
        return env;
    }

    @Test
    void productionRefusesTheDevelopmentWebSessionCookie() {
        MockEnvironment env = productionWebServer();
        // Exactly what the dev profile sets.
        env.setProperty("signature.remote-server.web.session.cookie-name", "SF_SESSION_DEV");
        env.setProperty("signature.remote-server.web.session.secure-cookie", "false");

        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("__Host- prefix")
                .hasMessageContaining("SIGNER_WEB_SESSION_SECURE=false");
    }

    @Test
    void productionRefusesASessionCookieWithoutTheHostPrefix() {
        MockEnvironment env = productionWebServer();
        env.setProperty("signature.remote-server.web.session.cookie-name", "SF_SESSION");

        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("__Host- prefix");
    }

    @Test
    void productionRefusesWebSessionsThatNeverTimeOut() {
        MockEnvironment zero = productionWebServer();
        zero.setProperty("signature.remote-server.web.session.idle-timeout", "PT0S");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(zero))
                .hasMessageContaining("SIGNER_WEB_SESSION_IDLE");

        MockEnvironment huge = productionWebServer();
        huge.setProperty("signature.remote-server.web.session.idle-timeout", "PT500H");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(huge))
                .hasMessageContaining("SIGNER_WEB_SESSION_IDLE");

        MockEnvironment longLived = productionWebServer();
        longLived.setProperty("signature.remote-server.web.session.absolute-timeout", "PT900H");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(longLived))
                .hasMessageContaining("SIGNER_WEB_SESSION_ABSOLUTE");
    }

    @Test
    void productionRefusesAnAbsoluteTimeoutShorterThanTheIdleOne() {
        MockEnvironment env = productionWebServer();
        env.setProperty("signature.remote-server.web.session.idle-timeout", "PT2H");
        env.setProperty("signature.remote-server.web.session.absolute-timeout", "PT1H");

        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("shorter than");
    }

    @Test
    void productionRefusesAnAnonymousIdleTimeoutLongerThanTheSignedInOne() {
        MockEnvironment env = productionWebServer();
        env.setProperty("signature.remote-server.web.session.idle-timeout", "PT30M");
        env.setProperty("signature.remote-server.web.session.anonymous-idle-timeout", "PT1H");

        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("SIGNER_WEB_SESSION_ANON_IDLE");
    }

    @Test
    void productionRefusesAnAnonymousIdleTimeoutOfZero() {
        MockEnvironment env = productionWebServer();
        env.setProperty("signature.remote-server.web.session.anonymous-idle-timeout", "PT0S");

        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("SIGNER_WEB_SESSION_ANON_IDLE");
    }

    @Test
    void productionRefusesAnUnparseableTimeout() {
        MockEnvironment env = productionWebServer();
        env.setProperty("signature.remote-server.web.session.idle-timeout", "30 minutes");

        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env))
                .hasMessageContaining("ISO-8601 duration");
    }

    @Test
    void theProductionWebSessionDefaultsAreAccepted() {
        // No web.session.* properties at all: the built-in defaults must pass.
        assertThatCode(() -> ProductionSafetyValidator.validate(productionWebServer()))
                .doesNotThrowAnyException();

        MockEnvironment explicit = productionWebServer();
        explicit.setProperty("signature.remote-server.web.session.cookie-name",
                "__Host-SF_SESSION");
        explicit.setProperty("signature.remote-server.web.session.secure-cookie", "true");
        explicit.setProperty("signature.remote-server.web.session.idle-timeout", "PT30M");
        explicit.setProperty("signature.remote-server.web.session.absolute-timeout", "PT8H");
        assertThatCode(() -> ProductionSafetyValidator.validate(explicit))
                .doesNotThrowAnyException();
    }

    @Test
    void theDevProfileMayUseAPlainHttpSessionCookie() {
        MockEnvironment dev = env("server", "dev");
        dev.setProperty("signature.remote-server.web.session.cookie-name", "SF_SESSION_DEV");
        dev.setProperty("signature.remote-server.web.session.secure-cookie", "false");

        assertThatCode(() -> ProductionSafetyValidator.validate(dev))
                .doesNotThrowAnyException();
    }
}

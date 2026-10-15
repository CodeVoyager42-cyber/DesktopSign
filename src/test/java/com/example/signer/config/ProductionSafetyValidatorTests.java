package com.example.signer.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

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
        assertThatCode(() -> ProductionSafetyValidator.validate(offloaded))
                .doesNotThrowAnyException();
    }

    @Test
    void devProfileIsAllowedButNeverInAPackagedBuild() {
        assertThatCode(() -> ProductionSafetyValidator.validate(env("desktop", "dev")))
                .doesNotThrowAnyException();
        System.setProperty("signflow.packaged", "true");
        assertThatThrownBy(() -> ProductionSafetyValidator.validate(env("desktop", "dev")))
                .hasMessageContaining("cannot be used by an installed SignFlow build");
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
}

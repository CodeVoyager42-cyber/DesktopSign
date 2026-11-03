package com.example.signer.token.mock;

import com.example.signer.exception.SignerException;
import com.example.signer.token.DevelopmentSigningIdentities;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.UnconfiguredSigningTokenProvider;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0.1 regression: the development mock token no longer ships publicly
 * derivable keys. Every installation generates its own random keys, the
 * certificates are visibly marked as development identities, and the mock
 * beans exist only in the {@code dev} profile.
 */
class MockTokenIdentityRegressionTests {

    private static List<String> fingerprints(MockSigningToken token) {
        return token.getSigningCertificates().stream()
                .map(SigningCertificate::getSha256Fingerprint)
                .toList();
    }

    @Test
    void twoFreshInstallationsGetDifferentSigningIdentities() throws Exception {
        Path installA = Files.createTempDirectory("install-a").resolve("dev-mock-token.p12");
        Path installB = Files.createTempDirectory("install-b").resolve("dev-mock-token.p12");

        List<String> a = fingerprints(new MockSigningToken(installA));
        List<String> b = fingerprints(new MockSigningToken(installB));

        assertThat(a).hasSize(3).doesNotHaveDuplicates();
        assertThat(b).hasSize(3);
        assertThat(a).doesNotContainAnyElementsOf(b);
    }

    @Test
    void noInstallationEverProducesThePubliclyKnownLegacyKeys() throws Exception {
        Path install = Files.createTempDirectory("install-c").resolve("dev-mock-token.p12");
        for (String fp : fingerprints(new MockSigningToken(install))) {
            assertThat(DevelopmentSigningIdentities.isLegacySharedFingerprint(fp)).isFalse();
        }
        for (String fp : fingerprints(new MockSigningToken())) {
            assertThat(DevelopmentSigningIdentities.isLegacySharedFingerprint(fp)).isFalse();
        }
    }

    @Test
    void anInstallationKeepsItsKeysAcrossRestarts() throws Exception {
        Path install = Files.createTempDirectory("install-d").resolve("dev-mock-token.p12");
        List<String> issued = fingerprints(new MockSigningToken(install));

        // Read the persisted keystore independently of the in-process cache:
        // a restarted app loads exactly these certificates.
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(install)) {
            ks.load(in, "signflow-dev-mock-token".toCharArray());
        }
        List<String> persisted = List.of(MockSigningToken.DEFAULT_ALIAS,
                        MockSigningToken.THIRD_ALIAS, MockSigningToken.EC_ALIAS).stream()
                .map(alias -> sha256((X509Certificate) certificate(ks, alias)))
                .toList();
        assertThat(persisted).isEqualTo(issued.stream()
                .map(fp -> fp.replace(":", "").toUpperCase()).toList());
    }

    @Test
    void mockCertificatesAreMarkedAsDevelopmentIdentities() {
        MockSigningToken token = new MockSigningToken();
        for (SigningCertificate cert : token.getSigningCertificates()) {
            assertThat(cert.getSubject()).contains(DevelopmentSigningIdentities.MOCK_MARKER_OU);
            assertThat(DevelopmentSigningIdentities.isDevelopmentIdentity(
                    cert.getSubject(), cert.getSha256Fingerprint())).isTrue();
        }
        // A real certificate (no marker, unknown fingerprint) is not.
        assertThat(DevelopmentSigningIdentities.isDevelopmentIdentity(
                "CN=Jane Doe,O=ACME,C=FR", "AB".repeat(32))).isFalse();
        // The legacy shared keys are recognised with or without colons.
        String legacy = DevelopmentSigningIdentities.LEGACY_SHARED_FINGERPRINTS.iterator().next();
        assertThat(DevelopmentSigningIdentities.isLegacySharedFingerprint(
                legacy.replaceAll("(..)(?!$)", "$1:").toLowerCase())).isTrue();
    }

    @Test
    void mockBeansAreDevelopmentOnlyAndProductionHasNoFallback() {
        assertThat(MockSigningToken.class.getAnnotation(Profile.class).value())
                .containsExactly("dev");
        assertThat(MockSigningTokenProvider.class.getAnnotation(Profile.class).value())
                .containsExactly("dev");
        assertThat(UnconfiguredSigningTokenProvider.class.getAnnotation(Profile.class).value())
                .containsExactly("!dev");
        // Production without PKCS#11: token detection fails with a clear
        // configuration error instead of handing out a software key.
        assertThatThrownBy(() -> new UnconfiguredSigningTokenProvider().findAvailable())
                .isInstanceOf(SignerException.class)
                .hasMessageContaining("PKCS");
    }

    private static java.security.cert.Certificate certificate(KeyStore ks, String alias) {
        try {
            return ks.getCertificate(alias);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(X509Certificate cert) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

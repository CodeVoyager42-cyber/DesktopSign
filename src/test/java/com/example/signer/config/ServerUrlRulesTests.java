package com.example.signer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a coordination-server address may be. The same rules decide whether
 * {@code scripts/package.sh} will bake an address into an installer and
 * whether {@link DesktopSettingsFile} will accept one from an administrator,
 * so the matrix is pinned once, here.
 */
class ServerUrlRulesTests {

    @Test
    void anHttpsHostIsAccepted() {
        assertThat(ServerUrlRules.problem("https://signflow.example.com", false)).isNull();
        assertThat(ServerUrlRules.isValid("https://signflow.example.com", false)).isTrue();
    }

    @Test
    void aPortIsAccepted() {
        assertThat(ServerUrlRules.problem("https://signflow.example.com:8443", false)).isNull();
    }

    @Test
    void plainHttpIsRefusedUnlessItIsExplicitlyAllowed() {
        assertThat(ServerUrlRules.problem("http://signflow.example.com", false))
                .contains("must use https://");
        // --local / a test deployment: the caller opts in, and says so.
        assertThat(ServerUrlRules.problem("http://localhost:8080", true)).isNull();
    }

    @Test
    void aMissingAddressIsAProblemInItsOwnRight() {
        assertThat(ServerUrlRules.problem(null, false)).contains("no coordination server address");
        assertThat(ServerUrlRules.problem("", false)).contains("no coordination server address");
        assertThat(ServerUrlRules.problem("   ", false)).contains("no coordination server address");
    }

    @Test
    void aSchemeIsRequired() {
        assertThat(ServerUrlRules.problem("signflow.example.com", false)).contains("needs a scheme");
    }

    @Test
    void anotherSchemeIsRefused() {
        assertThat(ServerUrlRules.problem("ftp://signflow.example.com", false))
                .contains("must use https://");
        assertThat(ServerUrlRules.problem("file:///etc/passwd", false))
                .contains("must use https://");
    }

    @Test
    void aHostIsRequired() {
        assertThat(ServerUrlRules.problem("https://", false)).isNotNull();
        assertThat(ServerUrlRules.problem("https:///somewhere", false)).isNotNull();
    }

    /**
     * A credential in the address would end up in a build argument, a
     * settings file on disk, and the start-up log line that reports it.
     */
    @Test
    void userInformationIsRefused() {
        assertThat(ServerUrlRules.problem("https://user:pw@signflow.example.com", false))
                .contains("must not carry user information");
    }

    /**
     * This is a base address request paths are appended to, so a path or a
     * trailing slash silently produces a wrong URL at the far end.
     */
    @Test
    void aPathQueryFragmentOrTrailingSlashIsRefused() {
        assertThat(ServerUrlRules.problem("https://signflow.example.com/", false))
                .contains("trailing slash");
        assertThat(ServerUrlRules.problem("https://signflow.example.com/api", false))
                .contains("no path");
        assertThat(ServerUrlRules.problem("https://signflow.example.com?x=1", false))
                .contains("query string");
        assertThat(ServerUrlRules.problem("https://signflow.example.com#frag", false))
                .contains("fragment");
    }

    @Test
    void surroundingWhitespaceIsRefusedRatherThanQuietlyTrimmed() {
        assertThat(ServerUrlRules.problem(" https://signflow.example.com", false))
                .contains("whitespace");
        assertThat(ServerUrlRules.problem("https://signflow.example.com ", false))
                .contains("whitespace");
    }

    @Test
    void aMalformedAddressIsReportedRatherThanThrown() {
        assertThat(ServerUrlRules.problem("https://signflow example.com", false)).isNotNull();
        assertThat(ServerUrlRules.problem("ht tps://x", false)).isNotNull();
    }

    @Test
    void loopbackIsRecognisedWhereverItIsWritten() {
        assertThat(ServerUrlRules.isLoopback("http://localhost:8080")).isTrue();
        assertThat(ServerUrlRules.isLoopback("https://127.0.0.1")).isTrue();
        assertThat(ServerUrlRules.isLoopback("http://127.9.9.9:8080")).isTrue();
        assertThat(ServerUrlRules.isLoopback("https://signflow.example.com")).isFalse();
        assertThat(ServerUrlRules.isLoopback("")).isFalse();
        assertThat(ServerUrlRules.isLoopback(null)).isFalse();
    }
}

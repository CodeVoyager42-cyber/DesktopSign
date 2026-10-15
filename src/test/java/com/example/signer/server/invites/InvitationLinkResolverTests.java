package com.example.signer.server.invites;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The emailed invitation link is useless unless the SIGNER can open it, so
 * the base URL resolution is a contract worth pinning: an explicit public
 * address always wins, a real (non-loopback) {@code APP_BASE_URL} is left
 * alone, and a loopback configuration is upgraded to this machine's LAN
 * address instead of silently emailing a {@code localhost} link.
 *
 * <p>Pure address handling — no Spring context, no network traffic.
 */
class InvitationLinkResolverTests {

    /** Builds a resolver and runs the startup resolution once. */
    private static InvitationLinkResolver resolveWith(
            String appBaseUrl, String publicBaseUrl, int serverPort) {
        InvitationLinkResolver resolver =
                new InvitationLinkResolver(appBaseUrl, publicBaseUrl, serverPort);
        resolver.resolve();
        return resolver;
    }

    /* ── explicit configuration wins ─────────────────────────────────── */

    @Test
    void explicitPublicBaseUrlWinsAndIsReportedVerbatim() {
        InvitationLinkResolver links = resolveWith(
                "http://localhost:8080",
                "https://signflow.example.com/", 8080);

        assertThat(links.baseUrl()).isEqualTo("https://signflow.example.com");
        assertThat(links.source()).isEqualTo("SIGNER_PUBLIC_BASE_URL");
        assertThat(links.localOnly()).isFalse();
    }

    @Test
    void aLoopbackPublicBaseUrlIsHonouredButFlaggedAsLocalOnly() {
        // The operator asked for it, so we build the links — but a link no
        // remote signer can open must never be presented as working.
        InvitationLinkResolver links = resolveWith(
                "http://localhost:8080", "http://127.0.0.1:8080", 8080);

        assertThat(links.baseUrl()).isEqualTo("http://127.0.0.1:8080");
        assertThat(links.source()).isEqualTo("SIGNER_PUBLIC_BASE_URL");
        assertThat(links.localOnly()).isTrue();
    }

    @Test
    void nonLoopbackAppBaseUrlIsUsedUnchanged() {
        InvitationLinkResolver links = resolveWith(
                "http://192.168.4.31:8080", "", 8080);

        assertThat(links.baseUrl()).isEqualTo("http://192.168.4.31:8080");
        assertThat(links.source()).isEqualTo("APP_BASE_URL");
        assertThat(links.localOnly()).isFalse();
    }

    /* ── loopback configuration is upgraded ──────────────────────────── */

    @Test
    void loopbackAppBaseUrlIsReplacedByThisMachinesLanAddress() {
        String lanHost = InvitationLinkResolver.detectLanHost();
        Assumptions.assumeTrue(lanHost != null,
                "no non-virtual LAN adapter on this machine");

        InvitationLinkResolver links = resolveWith(
                "http://localhost:8080", "", 8080);

        assertThat(links.baseUrl()).isEqualTo("http://" + lanHost + ":8080");
        assertThat(links.source()).isEqualTo("auto-detected LAN address");
        assertThat(links.localOnly()).isFalse();
        assertThat(links.baseUrl()).doesNotContain("localhost", "127.0.0.1");
    }

    @Test
    void replacingTheHostKeepsTheSchemePortAndPathPrefix() {
        String lanHost = InvitationLinkResolver.detectLanHost();
        Assumptions.assumeTrue(lanHost != null,
                "no non-virtual LAN adapter on this machine");

        InvitationLinkResolver links = resolveWith(
                "https://127.0.0.1:8443/signflow", "", 8080);

        assertThat(links.baseUrl())
                .isEqualTo("https://" + lanHost + ":8443/signflow");
    }

    @Test
    void theServerPortIsAddedWhenTheConfiguredUrlOmitsOne() {
        String lanHost = InvitationLinkResolver.detectLanHost();
        Assumptions.assumeTrue(lanHost != null,
                "no non-virtual LAN adapter on this machine");

        InvitationLinkResolver links = resolveWith("http://localhost", "", 8080);

        assertThat(links.baseUrl()).isEqualTo("http://" + lanHost + ":8080");
    }

    /* ── no usable address: warn instead of pretending ───────────────── */

    @Test
    void withoutAnyLanAddressTheLoopbackUrlIsKeptAndFlaggedLocalOnly() {
        InvitationLinkResolver links = new InvitationLinkResolver(
                "http://localhost:8080", "", 8080) {
            @Override
            String detectLanHostAddress() {
                return null;
            }
        };
        links.resolve();

        assertThat(links.baseUrl()).isEqualTo("http://localhost:8080");
        assertThat(links.localOnly()).isTrue();
    }

    /* ── the detection helpers themselves ───────────────────────────── */

    @Test
    void detectedLanAddressIsARealIpv4AddressNotLoopbackOrVirtual() {
        String lanHost = InvitationLinkResolver.detectLanHost();
        Assumptions.assumeTrue(lanHost != null,
                "no non-virtual LAN adapter on this machine");

        assertThat(lanHost)
                .matches("\\d{1,3}(\\.\\d{1,3}){3}")
                .doesNotStartWith("127.")
                .doesNotStartWith("169.254.");
    }

    @Test
    void loopbackBaseUrlsAreRecognisedInEverySpelling() {
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl(null)).isTrue();
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl("")).isTrue();
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl("   ")).isTrue();
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl("not a url")).isTrue();
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl(
                "http://localhost:8080")).isTrue();
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl(
                "http://localhost:8080/invitations/x")).isTrue();
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl(
                "http://127.0.0.1:3000")).isTrue();
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl(
                "http://127.5.6.7:3000")).isTrue();
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl(
                "http://[::1]:8080")).isTrue();
    }

    @Test
    void nonLoopbackBaseUrlsAreNotMistakenForLocalOnes() {
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl(
                "http://192.168.4.31:8080")).isFalse();
        // Anything that resolves to a real address, e.g. a LAN name.
        assertThat(InvitationLinkResolver.isLoopbackBaseUrl(
                "http://signflow.example.com")).isFalse();
    }
}

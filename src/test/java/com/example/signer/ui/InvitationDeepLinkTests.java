package com.example.signer.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Clicking the emailed link launches the app with
 * {@code signflow://invitation/<token>}; the token must be extracted so the
 * dialog skips the manual paste step.
 */
class InvitationDeepLinkTests {

    @Test
    void extractsTheTokenFromTheDeepLink() {
        String token = "YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms";
        assertThat(InvitationLinks.extractInvitationToken(
                List.of("signflow://invitation/" + token))).isEqualTo(token);
    }

    @Test
    void extractsABareTokenArgument() {
        assertThat(InvitationLinks.extractInvitationToken(
                List.of("YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms")))
                .isEqualTo("YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms");
    }

    @Test
    void returnsNullForANormalLaunch() {
        assertThat(InvitationLinks.extractInvitationToken(List.of())).isNull();
        assertThat(InvitationLinks.extractInvitationToken(
                List.of("-Dfoo=bar", "--profile", "desktop"))).isNull();
    }

    @Test
    void ignoresArgumentsThatAreNotInvitationLinks() {
        assertThat(InvitationLinks.extractInvitationToken(
                List.of("signflow://invitation/short"))).isNull();
        assertThat(InvitationLinks.extractInvitationToken(
                List.of("https://example.com/signflow"))).isNull();
    }

    @Test
    void parsesAVersionOneLaunchLinkStrictly() {
        String code = "YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms";   // 43 chars
        InvitationLinks.LaunchArgument a = InvitationLinks.parse(
                List.of("signflow://launch?v=1&c=" + code));
        assertThat(a.kind()).isEqualTo(InvitationLinks.LaunchArgument.Kind.LAUNCH);
        assertThat(a.value()).isEqualTo(code);
        assertThat(a.toString()).doesNotContain(code);
        // Browsers sometimes add a slash before the query.
        assertThat(InvitationLinks.parse(List.of("signflow://launch/?v=1&c=" + code)).value())
                .isEqualTo(code);
        // Anything else is not a launch link.
        for (String bad : new String[] {
                "signflow://launch?v=2&c=" + code,
                "signflow://launch?c=" + code,
                "signflow://launch?v=1&c=" + code + "&x=1",
                "signflow://launch?v=1&c=" + code.substring(1),
                "signflow://launch?v=1&c=" + code.replace('Y', '%'),
                "xsignflow://launch?v=1&c=" + code,
                "signflow://launch?v=1&c=" + code + "/"}) {
            assertThat(InvitationLinks.launchCode(bad)).as(bad).isNull();
        }
    }

    @Test
    void aLaunchLinkWinsAndIsNeverMistakenForAnInvitation() {
        String code = "YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms";
        assertThat(InvitationLinks.extractInvitationToken(
                List.of("signflow://launch?v=1&c=" + code))).isNull();
        InvitationLinks.LaunchArgument a = InvitationLinks.parse(List.of(
                "signflow://invitation/" + code, "signflow://launch?v=1&c=" + code));
        assertThat(a.kind()).isEqualTo(InvitationLinks.LaunchArgument.Kind.LAUNCH);
        assertThat(InvitationLinks.parse(List.of("--spring.profiles.active=dev"))).isNull();
    }
}

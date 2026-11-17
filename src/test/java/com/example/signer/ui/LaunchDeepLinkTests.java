package com.example.signer.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Reading the {@code signflow://} link the app was started with.
 *
 * <p>The launch link is the product's way into the desktop and its parsing is
 * pinned here, unchanged. What is gone from this file is the invitation
 * link's refusal: three tests covered a message explaining that e-mail
 * invitations had been removed, and that explanation has been removed in its
 * turn. The one case kept from it is the one that still says something about
 * today's behaviour — an invitation-shaped link is simply not a launch link.
 */
class LaunchDeepLinkTests {

    private static final String CODE = "YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms"; // 43

    @Test
    void parsesAVersionOneLaunchLinkStrictly() {
        LaunchLinks.LaunchArgument a = LaunchLinks.parse(
                List.of("signflow://launch?v=1&c=" + CODE));
        assertThat(a.kind()).isEqualTo(LaunchLinks.LaunchArgument.Kind.LAUNCH);
        assertThat(a.value()).isEqualTo(CODE);
        assertThat(a.toString()).doesNotContain(CODE);
        // Browsers sometimes add a slash before the query.
        assertThat(LaunchLinks.parse(List.of("signflow://launch/?v=1&c=" + CODE)).value())
                .isEqualTo(CODE);
        // Anything else is not a launch link.
        for (String bad : new String[] {
                "signflow://launch?v=2&c=" + CODE,
                "signflow://launch?c=" + CODE,
                "signflow://launch?v=1&c=" + CODE + "&x=1",
                "signflow://launch?v=1&c=" + CODE.substring(1),
                "signflow://launch?v=1&c=" + CODE.replace('Y', '%'),
                "xsignflow://launch?v=1&c=" + CODE,
                "signflow://launch?v=1&c=" + CODE + "/"}) {
            assertThat(LaunchLinks.launchCode(bad)).as(bad).isNull();
        }
    }

    @Test
    void anOrdinaryLaunchCarriesNoLink() {
        assertThat(LaunchLinks.parse(List.of())).isNull();
        assertThat(LaunchLinks.parse(List.of("--spring.profiles.active=dev"))).isNull();
        assertThat(LaunchLinks.parse(List.of("-Dfoo=bar", "--profile", "desktop"))).isNull();
    }

    @Test
    void aBareTokenIsNotALaunchLink() {
        // It was read as an invitation token two removals ago. Nothing reads a
        // bare argument now, so it carries no launch.
        assertThat(LaunchLinks.parse(List.of(CODE))).isNull();
    }

    @Test
    void anOldInvitationLinkCarriesNoLaunch() {
        // No longer refused with an explanation, just not a launch link: the
        // app opens as it does from its icon. Pinned because that is a
        // deliberate change, not an accident of the pattern.
        assertThat(LaunchLinks.parse(List.of("signflow://invitation/" + CODE))).isNull();
    }

    @Test
    void aLaunchLinkIsFoundAmongOtherArguments() {
        LaunchLinks.LaunchArgument a = LaunchLinks.parse(List.of(
                "signflow://invitation/" + CODE,
                "--spring.profiles.active=dev",
                "signflow://launch?v=1&c=" + CODE));
        assertThat(a.kind()).isEqualTo(LaunchLinks.LaunchArgument.Kind.LAUNCH);
        assertThat(a.value()).isEqualTo(CODE);
    }
}

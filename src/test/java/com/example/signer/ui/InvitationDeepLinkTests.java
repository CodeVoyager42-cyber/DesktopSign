package com.example.signer.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Reading the {@code signflow://} link the app was started with.
 *
 * <p>The launch link is the product's way into the desktop and its parsing
 * is pinned here, unchanged. The invitation link is the removed workflow's:
 * it must be refused, and refused visibly, so somebody clicking an old
 * e-mail is told why nothing happened.
 */
class InvitationDeepLinkTests {

    private static final String CODE = "YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms"; // 43

    @Test
    void parsesAVersionOneLaunchLinkStrictly() {
        InvitationLinks.LaunchArgument a = InvitationLinks.parse(
                List.of("signflow://launch?v=1&c=" + CODE));
        assertThat(a.kind()).isEqualTo(InvitationLinks.LaunchArgument.Kind.LAUNCH);
        assertThat(a.value()).isEqualTo(CODE);
        assertThat(a.toString()).doesNotContain(CODE);
        // Browsers sometimes add a slash before the query.
        assertThat(InvitationLinks.parse(List.of("signflow://launch/?v=1&c=" + CODE)).value())
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
            assertThat(InvitationLinks.launchCode(bad)).as(bad).isNull();
        }
    }

    @Test
    void anOrdinaryLaunchCarriesNoLink() {
        assertThat(InvitationLinks.parse(List.of())).isNull();
        assertThat(InvitationLinks.parse(List.of("--spring.profiles.active=dev"))).isNull();
        assertThat(InvitationLinks.parse(List.of("-Dfoo=bar", "--profile", "desktop"))).isNull();
    }

    @Test
    void anInvitationLinkIsRefusedAndNeverOpened() {
        List<String> args = List.of("signflow://invitation/" + CODE);
        assertThat(InvitationLinks.parse(args)).isNull();
        assertThat(InvitationLinks.mentionsRemovedInvitationLink(args)).isTrue();
    }

    @Test
    void aBareTokenIsNoLongerAccepted() {
        // It used to be read as an invitation token. There is nothing left to
        // redeem, so it is neither parsed nor reported as an invitation link.
        List<String> args = List.of(CODE);
        assertThat(InvitationLinks.parse(args)).isNull();
        assertThat(InvitationLinks.mentionsRemovedInvitationLink(args)).isFalse();
    }

    @Test
    void theRefusalIsReportedOnlyForARealInvitationLink() {
        assertThat(InvitationLinks.mentionsRemovedInvitationLink(List.of())).isFalse();
        assertThat(InvitationLinks.mentionsRemovedInvitationLink(
                List.of("signflow://invitation/short"))).isFalse();
        assertThat(InvitationLinks.mentionsRemovedInvitationLink(
                List.of("https://example.com/signflow"))).isFalse();
        assertThat(InvitationLinks.mentionsRemovedInvitationLink(
                List.of("signflow://launch?v=1&c=" + CODE))).isFalse();
    }

    @Test
    void aLaunchLinkWinsOverAnInvitationLinkInTheSameArguments() {
        InvitationLinks.LaunchArgument a = InvitationLinks.parse(List.of(
                "signflow://invitation/" + CODE, "signflow://launch?v=1&c=" + CODE));
        assertThat(a.kind()).isEqualTo(InvitationLinks.LaunchArgument.Kind.LAUNCH);
        assertThat(a.value()).isEqualTo(CODE);
    }

    @Test
    void theRefusalMessageSendsTheUserToTheWebsiteAndNamesNoToken() {
        assertThat(InvitationLinks.INVITATION_LINK_REMOVED_MESSAGE)
                .contains("no longer works")
                .contains("website")
                .contains("Sign")
                .doesNotContain(CODE);
    }
}

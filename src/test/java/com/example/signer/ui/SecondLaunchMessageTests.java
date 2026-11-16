package com.example.signer.ui;

import com.example.signer.ui.InvitationLinks.LaunchArgument;
import com.example.signer.ui.SingleInstanceGuard.Outcome;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a second launch says, and what reaches Spring (Phase B remediation). */
class SecondLaunchMessageTests {

    private static final String CODE = "YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms";

    @Test
    void aLaunchLinkThatWasNotPassedOnSendsTheUserBackToTheWebsite() {
        String text = SignerFxApplication.alreadyRunningMessage(Outcome.ALREADY_RUNNING_UNVERIFIED,
                new LaunchArgument(LaunchArgument.Kind.LAUNCH, CODE));
        assertThat(text).contains("not passed on").contains("website").contains("2 minutes")
                .doesNotContain(CODE).doesNotContain("open the link again from there");
    }

    @Test
    void aBusyCopyIsNotCalledDeadAndNoLinkJustPointsAtTheWindow() {
        String text = SignerFxApplication.alreadyRunningMessage(Outcome.OTHER_UNRESPONSIVE,
                new LaunchArgument(LaunchArgument.Kind.LAUNCH, CODE));
        assertThat(text).contains("busy").contains("website")
                .doesNotContain("stuck").doesNotContain("Restart").doesNotContain(CODE);
        assertThat(SignerFxApplication.alreadyRunningMessage(Outcome.ALREADY_RUNNING_UNVERIFIED,
                null)).contains("Switch to the open SignFlow window");
    }

    @Test
    void launchLinksNeverReachSpring() {
        // An invitation link is no longer honoured, but it must still be kept
        // away from Spring: it is somebody's old one-time token.
        String[] args = SignerFxApplication.springArguments(List.of(
                "signflow://launch?v=1&c=" + CODE, "--spring.profiles.active=dev",
                "signflow://invitation/" + "t".repeat(40), CODE));
        assertThat(args).containsExactly("--spring.profiles.active=dev");
    }
}

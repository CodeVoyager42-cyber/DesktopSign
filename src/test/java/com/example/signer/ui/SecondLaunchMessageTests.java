package com.example.signer.ui;

import com.example.signer.ui.LaunchLinks.LaunchArgument;
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
                new LaunchArgument(CODE));
        assertThat(text).contains("not passed on").contains("website").contains("2 minutes")
                .doesNotContain(CODE);
    }

    @Test
    void aBusyCopyIsNotCalledDead() {
        String text = SignerFxApplication.alreadyRunningMessage(Outcome.OTHER_UNRESPONSIVE,
                new LaunchArgument(CODE));
        assertThat(text).contains("busy").contains("website")
                .doesNotContain("stuck").doesNotContain("Restart").doesNotContain(CODE);
    }

    @Test
    void withoutALinkTheUserIsOnlyAskedToSwitchWindows() {
        assertThat(SignerFxApplication.alreadyRunningMessage(
                Outcome.ALREADY_RUNNING_UNVERIFIED, null))
                .contains("Switch to the open SignFlow window");
    }

    @Test
    void launchLinksNeverReachSpring() {
        String[] args = SignerFxApplication.springArguments(List.of(
                "signflow://launch?v=1&c=" + CODE, "--spring.profiles.active=dev", CODE));
        assertThat(args).containsExactly("--spring.profiles.active=dev");
    }
}

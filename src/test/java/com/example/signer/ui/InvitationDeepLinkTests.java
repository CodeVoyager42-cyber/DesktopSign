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
    void parsesTheTokenForwardedByASecondLaunch() {
        String token = "YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms";
        assertThat(InvitationLinks.parseActivationToken("token=" + token))
                .isEqualTo(token);
        assertThat(InvitationLinks.parseActivationToken("x=1&token=" + token))
                .isEqualTo(token);
    }

    @Test
    void forwardedQueryWithoutTokenMeansRaiseOnly() {
        assertThat(InvitationLinks.parseActivationToken(null)).isNull();
        assertThat(InvitationLinks.parseActivationToken("x=1")).isNull();
        assertThat(InvitationLinks.parseActivationToken("token=")).isNull();
    }

    @Test
    void rejectsMalformedForwardedTokens() {
        String token = "YRVKvELD1AlB94FtZnHo5B-Ydam4b43URdsizGjgnms";
        // %3C = '<' — outside the token character set, so this is rejected.
        assertThat(InvitationLinks.parseActivationToken(
                "token=" + token + "%3Cscript%3E")).isNull();
        // Too short to be an invitation token.
        assertThat(InvitationLinks.parseActivationToken(
                "token=abc")).isNull();
    }
}
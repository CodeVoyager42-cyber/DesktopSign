package com.example.signer.server.invites;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The emailed link resolves to this page; it must show the request without
 * leaking internals, and every failure mode must stay friendly.
 */
class SignPageRendererTests {

    private static SignPageRenderer.View view() {
        return new SignPageRenderer.View("CV.pdf", "alice", "mouadm7c7@gmail.com",
                "October 6, 2026 18:32", "signflow://invitation/tok123",
                "/sign/tok123/document", "SignFlow", false);
    }

    @Test
    void liveInvitationShowsRequestAndBothActions() {
        String html = SignPageRenderer.pending(view());

        assertThat(html).contains("Signature required");
        assertThat(html).contains("CV.pdf");
        assertThat(html).contains("alice");
        assertThat(html).contains("mouadm7c7@gmail.com");
        assertThat(html).contains("Review &amp; Sign — opens SignFlow");
        assertThat(html).contains("href=\"signflow://invitation/tok123\"");
        assertThat(html).contains("View the document");
        assertThat(html).contains("/sign/tok123/document");
    }

    @Test
    void entryPageOffersDownloadWhenInstallerUrlIsConfigured() {
        SignPageRenderer.View withDownload = new SignPageRenderer.View(
                "CV.pdf", "alice", "mouadm7c7@gmail.com", "October 6, 2026 18:32",
                "signflow://invitation/tok123", "/sign/tok123/document",
                "SignFlow", false, "https://signflow.example.com/download");
        String html = SignPageRenderer.pending(withDownload);

        assertThat(html).contains("Don't have SignFlow yet?");
        assertThat(html).contains("Download SignFlow");
        assertThat(html).contains("href=\"https://signflow.example.com/download\"");
        assertThat(html).contains("Already installed?");
    }

    @Test
    void withoutInstallerUrlTheFallbackIsHonestNotFake() {
        String html = SignPageRenderer.pending(view()); // downloadUrl = null

        assertThat(html).contains("Don't have SignFlow yet?");
        assertThat(html).contains("distributed by your organisation");
        assertThat(html).doesNotContain("Download SignFlow");
    }

    @Test
    void pendingPageIsResponsiveAndShowsProgress() {
        String html = SignPageRenderer.pending(view());

        assertThat(html).contains("viewport");
        assertThat(html).contains("@media (max-width:460px)");
        assertThat(html).contains("prefers-color-scheme:dark");
        assertThat(html).contains("Awaiting your signature");
        assertThat(html).contains("You have been invited to sign a document");
        assertThat(html).contains("1 · Invited");
        assertThat(html).doesNotContain("tok123 <"); // no token echoed as text
    }

    @Test
    void noticesCarryAMatchingStatusBadge() {
        assertThat(SignPageRenderer.notice("Link expired", "d", "SignFlow"))
                .contains("pill--warn");
        assertThat(SignPageRenderer.notice("Link withdrawn", "d", "SignFlow"))
                .contains("pill--bad");
        assertThat(SignPageRenderer.notice("Link not valid", "d", "SignFlow"))
                .contains("pill--bad");
        assertThat(SignPageRenderer.notice("Already accepted", "d", "SignFlow"))
                .contains("pill--ok");
        assertThat(SignPageRenderer.notice("Something went wrong", "d", "SignFlow"))
                .contains("pill--info");
    }

    @Test
    void invitedSignersAreNeverAskedToSignInOrRegister() {
        for (boolean accountExists : new boolean[]{true, false}) {
            String html = SignPageRenderer.pending(new SignPageRenderer.View(
                    "CV.pdf", "alice", "mouadm7c7@gmail.com", "October 6, 2026 18:32",
                    "signflow://invitation/tok123", "/sign/tok123/document",
                    "SignFlow", accountExists));

            assertThat(html).contains("no SignFlow account is required");
            assertThat(html.toLowerCase()).doesNotContain("sign in")
                    .doesNotContain("create your account")
                    .doesNotContain("password");
        }
    }

    @Test
    void noticesAreFriendlyAndOfferAFreshLink() {
        for (String heading : new String[]{"Link not valid", "Link expired",
                "Link withdrawn", "Already accepted"}) {
            String html = SignPageRenderer.notice(heading, "Some detail.", "SignFlow");

            assertThat(html).contains(heading);
            assertThat(html).contains("Some detail.");
            assertThat(html).contains("invite you again");
            assertThat(html).doesNotContain("Exception");
            assertThat(html).doesNotContain("stacktrace");
        }
    }
}
package com.example.signer.server.invites;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * The invitation email is the contract with the signer: subject, call to
 * action and link must never regress. Pure rendering, no Spring.
 */
class InvitationEmailTemplateTests {

    private static final Instant EXPIRES = Instant.parse("2026-10-06T12:00:00Z");
    private static final String URL = "http://localhost:3000/sign/abc123";

    @Test
    void subjectFollowsTheSignatureRequiredFormat() {
        assertThat(InvitationEmailTemplate.subject("CV.pdf"))
                .isEqualTo("Signature Required: CV.pdf");
    }

    @Test
    void htmlContainsTheCallToActionButtonPointingAtTheInvitationUrl() {
        String html = InvitationEmailTemplate.html("Mouadm", "CV.pdf", "alice",
                URL, EXPIRES, "SignFlow");

        assertThat(html).contains("Review &amp; Sign Document");
        assertThat(html).contains("href=\"" + URL + "\"");
        assertThat(html).contains("Hello Mouadm,");
        assertThat(html).contains("<b>CV.pdf</b>");
        assertThat(html).contains("Requested by alice");
        assertThat(html).contains("This link is unique to you");
        assertThat(html).contains("If you were not expecting this request");
        assertThat(html).contains("Regards,");
        assertThat(html).contains("SignFlow");
    }

    @Test
    void plainTextAlternativeCarriesTheSameLink() {
        String text = InvitationEmailTemplate.text("Mouadm", "CV.pdf", "alice",
                URL, EXPIRES, "SignFlow");

        assertThat(text).contains(URL);
        assertThat(text).contains("Hello Mouadm,");
        assertThat(text).contains("Signature Required".substring(0, 0) + "CV.pdf");
    }

    @Test
    void missingRecipientNameFallsBackToNeutralGreeting() {
        String html = InvitationEmailTemplate.html(null, "CV.pdf", "alice",
                URL, EXPIRES, "SignFlow");

        assertThat(html).contains("Hello,");
        assertThat(html).doesNotContain("Hello null");
    }

    @Test
    void documentNameIsHtmlEscapedSoTheBodyCannotBeBrokenUp() {
        String html = InvitationEmailTemplate.html("Mouadm",
                "<script>alert(1)</script>.pdf", "alice", URL, EXPIRES, "SignFlow");

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }
}
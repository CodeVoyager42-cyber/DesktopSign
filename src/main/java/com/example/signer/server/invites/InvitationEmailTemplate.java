package com.example.signer.server.invites;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders the "signature required" invitation email.
 *
 * <p>Pure rendering (no Spring, no SMTP) so the exact subject line, call to
 * action and link can be unit-tested. The emailed link is the public
 * invitation URL ({@code <app.base-url>/sign/<token>}); the token itself is
 * the only secret in it - no database id or credential is ever exposed.
 */
public final class InvitationEmailTemplate {

    /** Text of the call-to-action button in the email body. */
    public static final String CTA_TEXT = "Review &amp; Sign Document";

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private InvitationEmailTemplate() {
    }

    /** Subject line: "Signature Required: [Document Name]". */
    public static String subject(String documentName) {
        return "Signature Required: " + documentName;
    }

    public static String html(String recipientName, String documentName,
                              String inviterName, String invitationUrl,
                              Instant expiresAt, String appName) {
        String greeting = (recipientName == null || recipientName.isBlank())
                ? "Hello," : "Hello " + recipientName + ",";
        return """
                <div style="font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;
                            max-width:600px;margin:0 auto;color:#1c2530">
                  <p>%s</p>
                  <p>You have been requested to sign <b>%s</b>.</p>
                  <p>Please review the document and complete your signature
                     using the button below.</p>
                  <p style="margin:28px 0">
                    <a href="%s"
                       style="background:#12507d;color:#fff;text-decoration:none;
                              padding:14px 28px;border-radius:6px;
                              display:inline-block;font-weight:600">%s</a>
                  </p>
                  <p>This link is unique to you and should not be shared with
                     others.</p>
                  <p style="color:#6b7683;font-size:13px">Requested by %s &middot;
                     link valid until %s</p>
                  <p>If you were not expecting this request, you can safely
                     ignore this email.</p>
                  <p>Regards,<br>%s</p>
                </div>
                """.formatted(greeting, escape(documentName), escape(invitationUrl),
                CTA_TEXT, escape(inviterName), DATE.format(expiresAt),
                escape(appName));
    }

    /** Plain-text alternative (used by the console mailbox). */
    public static String text(String recipientName, String documentName,
                              String inviterName, String invitationUrl,
                              Instant expiresAt, String appName) {
        String greeting = (recipientName == null || recipientName.isBlank())
                ? "Hello," : "Hello " + recipientName + ",";
        return """
                %s

                You have been requested to sign %s.

                Please review the document and complete your signature using the link below:

                %s

                This link is unique to you and should not be shared with others.

                Requested by %s - link valid until %s

                If you were not expecting this request, you can safely ignore this email.

                Regards,
                %s
                """.formatted(greeting, documentName, invitationUrl, inviterName,
                DATE.format(expiresAt), appName);
    }

    private static String escape(String s) {
        return s == null ? "" : s
                .replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
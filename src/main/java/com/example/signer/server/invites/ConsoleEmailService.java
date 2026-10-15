package com.example.signer.server.invites;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Development email delivery (brief §18/§19): the invitation is shown on the
 * server console instead of being sent.
 *
 * <p>Secret hygiene (P0.11): the server LOG never receives the raw one-time
 * invitation token — the logged copy of the email has the token redacted
 * (SHA-256 prefix). The complete email, with the usable link, is printed to
 * the process's standard output only, which is the developer's mailbox. The
 * production safety checks refuse console mode outside the {@code dev}
 * profile, so this never runs in production.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.email.mode",
        havingValue = "console", matchIfMissing = true)
public class ConsoleEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailService.class);

    private final String appName;

    public ConsoleEmailService(@Value("${app.name:SignFlow}") String appName) {
        this.appName = appName;
    }

    @Override
    public void sendSigningInvitation(String recipientEmail, String recipientName,
                                      String workflowName, String inviterName,
                                      String invitationUrl, Instant expiresAt) {
        String subject = InvitationEmailTemplate.subject(workflowName);
        String body = InvitationEmailTemplate.text(recipientName, workflowName,
                inviterName, invitationUrl, expiresAt, appName);
        log.info("EMAIL (console mode) to {} — subject '{}', link {}",
                recipientEmail, subject, SecretRedaction.redact(invitationUrl));
        System.out.println("\n================================================================================\n"
                + "  DEVELOPMENT MAILBOX (console mode) → " + recipientEmail + "\n"
                + "  Subject: " + subject + "\n\n"
                + body + "\n"
                + "================================================================================");
    }
}

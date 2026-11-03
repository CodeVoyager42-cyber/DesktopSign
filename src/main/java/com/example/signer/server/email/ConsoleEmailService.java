package com.example.signer.server.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Development email delivery: the message is shown on the server console
 * instead of being sent.
 *
 * <p>Secret hygiene (P0.11): the server LOG never receives a raw one-time
 * token. The complete message, with the usable link, is printed to the
 * process's standard output only, which is the developer's mailbox. The
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

    /** Development mailbox: the last account link sent to each address. */
    private final java.util.Map<String, String> latestAccountLinks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Development mailbox: the last account notice sent to each address. */
    private final java.util.Map<String, String> latestAccountNotices =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void sendEmailVerification(String recipientEmail, String link, Instant expiresAt) {
        latestAccountLinks.put(recipientEmail, link);
        // The link is a secret: the server log only says that one was sent.
        log.info("EMAIL (console mode) to {} — e-mail verification link", recipientEmail);
        print(recipientEmail, AccountEmailTemplate.verificationSubject(appName),
                AccountEmailTemplate.verificationText(link, expiresAt, appName));
    }

    @Override
    public void sendPasswordReset(String recipientEmail, String link, Instant expiresAt) {
        latestAccountLinks.put(recipientEmail, link);
        log.info("EMAIL (console mode) to {} — password-reset link", recipientEmail);
        print(recipientEmail, AccountEmailTemplate.resetSubject(appName),
                AccountEmailTemplate.resetText(link, expiresAt, appName));
    }

    @Override
    public void sendPasswordChanged(String recipientEmail, Instant changedAt,
                                    boolean afterReset) {
        String body = AccountEmailTemplate.changedText(changedAt, afterReset, appName);
        latestAccountNotices.put(recipientEmail, body);
        // There is no secret in this one, so saying what it was is safe.
        log.info("EMAIL (console mode) to {} — password-change notice", recipientEmail);
        print(recipientEmail, AccountEmailTemplate.changedSubject(appName), body);
    }

    @Override
    public void sendAccountLocked(String recipientEmail, Instant lockedUntil) {
        String body = AccountEmailTemplate.lockedOutText(lockedUntil, appName);
        latestAccountNotices.put(recipientEmail, body);
        // No secret in this one either.
        log.info("EMAIL (console mode) to {} — account-lock notice", recipientEmail);
        print(recipientEmail, AccountEmailTemplate.lockedOutSubject(appName), body);
    }

    /**
     * The last account link "sent" to {@code email} in console mode. Console
     * mode is refused outside the {@code dev} profile, so this mailbox never
     * exists on a production server.
     */
    public String latestAccountLink(String email) {
        return latestAccountLinks.get(email);
    }

    /** The last account notice (no link in it) "sent" to {@code email}. */
    public String latestAccountNotice(String email) {
        return latestAccountNotices.get(email);
    }

    private static void print(String recipient, String subject, String body) {
        System.out.println("\n================================================================================\n"
                + "  DEVELOPMENT MAILBOX (console mode) → " + recipient + "\n"
                + "  Subject: " + subject + "\n\n"
                + body + "\n"
                + "================================================================================");
    }
}

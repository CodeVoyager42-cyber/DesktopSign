package com.example.signer.server.mail;

import java.time.Instant;

/**
 * Email delivery abstraction (brief §18). Development uses
 * {@link ConsoleEmailService}; production configures
 * {@link SmtpEmailService} through {@code signature.remote-server.email.mode}
 * and {@code spring.mail.*} (credentials from the environment only).
 *
 * <p>Every message here concerns an ACCOUNT: confirming an address, setting a
 * new password, and the two notices that something happened to the account.
 * The signing-invitation and guest-code messages were removed with the
 * invitation workflow.
 *
 * <p><b>Every method is abstract, deliberately.</b> They used to be
 * {@code default} bodies that threw, so an implementation could support the
 * invitation mail and opt out of the account mail, or the reverse. With only
 * account mail left there is nothing to opt out of: an {@code EmailService}
 * that cannot send these four is not one. Abstract methods make that a
 * compile error, where a throwing default made it a runtime failure on the
 * day somebody tried to reset a password.
 */
public interface EmailService {

    /**
     * Sends the one-time link that confirms an e-mail address (security
     * Phase D). The link is a secret: implementations never log it.
     *
     * @param recipientEmail the address being confirmed, and the link's only
     *                       destination
     * @param link           one-time verification URL (raw token inside)
     * @param expiresAt      when the link stops working
     */
    void sendEmailVerification(String recipientEmail, String link, Instant expiresAt);

    /**
     * Sends the one-time link that sets a new password (security Phase D).
     * The link is a secret: implementations never log it.
     *
     * @param recipientEmail the account's address, and the link's only destination
     * @param link           one-time password-reset URL (raw token inside)
     * @param expiresAt      when the link stops working
     */
    void sendPasswordReset(String recipientEmail, String link, Instant expiresAt);

    /**
     * Tells the account holder that their password has just changed (security
     * Phase D1 risk D8). Carries <b>no link and no token</b>: it is a notice,
     * and a notice with a link in it is the shape of the phishing message an
     * attacker would send next.
     *
     * <p>Best-effort by contract — the caller has already changed the
     * password, so an implementation that throws must not be allowed to undo
     * that.
     *
     * @param recipientEmail the account's address
     * @param changedAt      when it changed
     * @param afterReset     true when a reset link was used, false when the
     *                       holder changed it knowing the old password
     */
    void sendPasswordChanged(String recipientEmail, Instant changedAt, boolean afterReset);

    /**
     * Tells the account holder that their account has been locked by failed
     * sign-ins (security Phase 2 risk L3). Carries <b>no link and no
     * token</b>, like the password-change notice, and does not say which
     * address the attempts came from.
     *
     * <p>Best-effort by contract: the lock is already in place when this is
     * sent, and a mail server that is down must not change that.
     *
     * <p>Unlike the password-change notice, this one <b>can be triggered by
     * someone other than the holder</b> — that is the whole point of it — so
     * the caller caps how often it may be sent per account. An implementation
     * does not need to.
     *
     * @param recipientEmail the account's own address, the only destination
     * @param lockedUntil    when the lock lifts by itself
     */
    void sendAccountLocked(String recipientEmail, Instant lockedUntil);
}

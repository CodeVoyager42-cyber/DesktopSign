package com.example.signer.server.email;

import java.time.Instant;

/**
 * Email delivery abstraction. Development uses {@link ConsoleEmailService};
 * production configures {@link SmtpEmailService} through
 * {@code signature.remote-server.email.mode} and {@code spring.mail.*}
 * (credentials from the environment only).
 *
 * <p>Every message here is about <b>one account and its own address</b>:
 * confirming it, resetting its password, and telling its holder when
 * something changed. Nothing is ever sent to a third party — SignFlow asks
 * nobody else to sign a document, so no address but the account holder's own
 * is ever a destination.
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
    default void sendEmailVerification(String recipientEmail, String link,
                                       Instant expiresAt) {
        throw new IllegalStateException(getClass().getSimpleName()
                + " cannot send e-mail verification links");
    }

    /**
     * Sends the one-time link that sets a new password (security Phase D).
     * The link is a secret: implementations never log it.
     *
     * @param recipientEmail the account's address, and the link's only destination
     * @param link           one-time password-reset URL (raw token inside)
     * @param expiresAt      when the link stops working
     */
    default void sendPasswordReset(String recipientEmail, String link, Instant expiresAt) {
        throw new IllegalStateException(getClass().getSimpleName()
                + " cannot send password-reset links");
    }

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
    default void sendPasswordChanged(String recipientEmail, Instant changedAt,
                                     boolean afterReset) {
        throw new IllegalStateException(getClass().getSimpleName()
                + " cannot send password-change notices");
    }

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
    default void sendAccountLocked(String recipientEmail, Instant lockedUntil) {
        throw new IllegalStateException(getClass().getSimpleName()
                + " cannot send account-lock notices");
    }
}

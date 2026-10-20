package com.example.signer.server.invites;

import java.time.Instant;

/**
 * Email delivery abstraction (brief §18). Development uses
 * {@link ConsoleEmailService}; production configures
 * {@link SmtpEmailService} through {@code signature.remote-server.email.mode}
 * and {@code spring.mail.*} (credentials from the environment only).
 *
 * <p>The invitation URL is a desktop deep link carrying the raw one-time
 * invitation token; the server stores only its hash.
 */
public interface EmailService {

    /**
     * Sends (or prints) a professional signing-invitation email (brief §19).
     *
     * @param recipientEmail normalized invited email
     * @param recipientName  display name, may be derived from the email
     * @param workflowName   document the recipient is asked to sign
     * @param inviterName    who requested the signature
     * @param invitationUrl  one-time invitation deep link (raw token inside)
     * @param expiresAt      when the invitation expires
     */
    void sendSigningInvitation(String recipientEmail, String recipientName,
                               String workflowName, String inviterName,
                               String invitationUrl, Instant expiresAt);

    /**
     * Sends the one-time code that opens a guest signing session (security
     * Phase 3). The code is a secret: implementations never log it.
     *
     * @param recipientEmail the invited address (the code's only destination)
     * @param workflowName   document the code is for
     * @param code           6-digit one-time code
     * @param expiresAt      when the code stops working
     */
    default void sendGuestVerificationCode(String recipientEmail, String workflowName,
                                           String code, Instant expiresAt) {
        // Fail loudly rather than pretend a code was delivered.
        throw new IllegalStateException(getClass().getSimpleName()
                + " cannot send guest verification codes");
    }
}

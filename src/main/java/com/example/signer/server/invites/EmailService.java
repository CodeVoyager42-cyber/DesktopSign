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
}

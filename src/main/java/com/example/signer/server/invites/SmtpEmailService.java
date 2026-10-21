package com.example.signer.server.invites;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.MessagingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Production email delivery through {@code spring.mail.*} (brief §18) —
 * credentials come exclusively from the environment; nothing is hard-coded.
 * Active when {@code signature.remote-server.email.mode=smtp}; fails with a
 * clear message when no {@link JavaMailSender} is configured.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.email.mode",
        havingValue = "smtp")
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final JavaMailSender mailSender;
    private final String from;
    private final String appName;

    public SmtpEmailService(JavaMailSender mailSender,
                            @Value("${signature.remote-server.email.from:signflow@localhost}")
                            String from,
                            @Value("${app.name:SignFlow}")
                            String appName) {
        this.mailSender = mailSender;
        this.from = from;
        this.appName = appName;
    }

    @Override
    public void sendSigningInvitation(String recipientEmail, String recipientName,
                                      String workflowName, String inviterName,
                                      String invitationUrl, Instant expiresAt) {
        String html = InvitationEmailTemplate.html(recipientName, workflowName,
                inviterName, invitationUrl, expiresAt, appName);
        String plain = InvitationEmailTemplate.text(recipientName, workflowName,
                inviterName, invitationUrl, expiresAt, appName);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipientEmail);
            helper.setSubject(InvitationEmailTemplate.subject(workflowName));
            helper.setText(plain, html);
            mailSender.send(message);
            log.info("Invitation email sent to {} for '{}'", recipientEmail, workflowName);
        } catch (MessagingException e) {
            throw new IllegalStateException("The invitation email could not be sent", e);
        }
    }

    @Override
    public void sendGuestVerificationCode(String recipientEmail, String workflowName,
                                          String code, Instant expiresAt) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipientEmail);
            helper.setSubject(GuestCodeEmailTemplate.subject(workflowName));
            helper.setText(GuestCodeEmailTemplate.text(workflowName, code, expiresAt, appName));
            mailSender.send(message);
            // Never the code itself.
            log.info("Guest verification code sent to {} for '{}'", recipientEmail, workflowName);
        } catch (MessagingException e) {
            throw new IllegalStateException("The verification email could not be sent", e);
        }
    }

    @Override
    public void sendEmailVerification(String recipientEmail, String link, Instant expiresAt) {
        send(recipientEmail, AccountEmailTemplate.verificationSubject(appName),
                AccountEmailTemplate.verificationText(link, expiresAt, appName),
                "e-mail verification link");
    }

    @Override
    public void sendPasswordReset(String recipientEmail, String link, Instant expiresAt) {
        send(recipientEmail, AccountEmailTemplate.resetSubject(appName),
                AccountEmailTemplate.resetText(link, expiresAt, appName),
                "password-reset link");
    }

    /** Sends one plain-text message. The body holds a secret, so it is never logged. */
    private void send(String recipientEmail, String subject, String body, String what) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipientEmail);
            helper.setSubject(subject);
            helper.setText(body);
            mailSender.send(message);
            // Never the link itself.
            log.info("Sent {} to {}", what, recipientEmail);
        } catch (MessagingException e) {
            throw new IllegalStateException("The " + what + " could not be sent", e);
        }
    }
}

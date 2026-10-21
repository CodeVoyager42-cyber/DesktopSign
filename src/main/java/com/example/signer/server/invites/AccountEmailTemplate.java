package com.example.signer.server.invites;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Text of the two account e-mails of security Phase D: verify an address, and
 * set a new password.
 *
 * <p>Both carry a one-time link. The link is the secret, so neither the
 * subject nor any log line ever contains it — only the templates below put it
 * in the body that goes to the recipient.
 *
 * <p>Deliberately plain text. These are security e-mails; there is nothing to
 * click except the one link, and no images or tracking to explain away.
 */
public final class AccountEmailTemplate {

    private static final DateTimeFormatter WHEN = DateTimeFormatter
            .ofPattern("d MMMM yyyy 'at' HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private AccountEmailTemplate() {
    }

    public static String verificationSubject(String appName) {
        return "Confirm your " + appName + " e-mail address";
    }

    public static String verificationText(String link, Instant expiresAt, String appName) {
        return "Welcome to " + appName + ".\n\n"
                + "Confirm this e-mail address by opening the link below:\n\n"
                + "  " + link + "\n\n"
                + "The link works once and expires on " + WHEN.format(expiresAt) + ".\n\n"
                + "You can sign in before confirming, but you will not be able to\n"
                + "create signing requests or sign documents until you do.\n\n"
                + "If you did not create an account, ignore this e-mail — nothing\n"
                + "happens until the link is opened.\n\n"
                + "Regards,\n" + appName;
    }

    public static String resetSubject(String appName) {
        return "Set a new " + appName + " password";
    }

    public static String resetText(String link, Instant expiresAt, String appName) {
        return "Someone asked to set a new password for this " + appName + " account.\n\n"
                + "If it was you, open the link below:\n\n"
                + "  " + link + "\n\n"
                + "The link works once and expires on " + WHEN.format(expiresAt) + ".\n\n"
                + "Setting a new password signs this account out everywhere, on the\n"
                + "website and in the desktop app.\n\n"
                + "If it was not you, ignore this e-mail: your password is unchanged,\n"
                + "and the link stops working on its own.\n\n"
                + "Regards,\n" + appName;
    }
}

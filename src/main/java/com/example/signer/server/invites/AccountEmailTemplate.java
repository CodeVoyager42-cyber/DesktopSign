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

    public static String changedSubject(String appName) {
        return "Your " + appName + " password was changed";
    }

    /**
     * The notice sent <b>after</b> a password has changed (security Phase D1
     * risk D8) — the usual way someone finds out that their account was taken
     * over.
     *
     * <p>It carries <b>no link and no token</b>, deliberately: a notice that
     * something went wrong is exactly the message an attacker would like to
     * dress up as a password-reset link. Whoever receives this has to go to
     * the site themselves.
     *
     * @param afterReset true when a reset link was used, false when the
     *                   account holder changed it knowing the old password —
     *                   worth telling apart, because only one of the two
     *                   needs the old password
     */
    public static String changedText(Instant changedAt, boolean afterReset, String appName) {
        return "The password for your " + appName + " account was "
                + (afterReset ? "reset using an e-mailed link" : "changed")
                + " on " + WHEN.format(changedAt) + ".\n\n"
                + (afterReset
                    ? "Whoever did this did not need your old password, only the link.\n\n"
                    : "Whoever did this had to know your old password.\n\n")
                + "This account is now signed out "
                + (afterReset ? "everywhere" : "everywhere except where the change was made")
                + ", on the website and in the desktop app.\n\n"
                + "If it was you, there is nothing to do.\n\n"
                + "If it was NOT you, someone else can get into your e-mail or your\n"
                + "account. Go to " + appName + " yourself — do not follow a link in any\n"
                + "e-mail about this — set a new password, and tell whoever runs your\n"
                + appName + " server.\n\n"
                + "This message contains no link for that reason.\n\n"
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

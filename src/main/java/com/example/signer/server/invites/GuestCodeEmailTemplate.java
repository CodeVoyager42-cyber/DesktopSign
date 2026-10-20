package com.example.signer.server.invites;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Text of the guest verification-code email (security Phase 3). */
final class GuestCodeEmailTemplate {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm 'on' MMMM d", Locale.ENGLISH)
                    .withZone(ZoneId.systemDefault());

    private GuestCodeEmailTemplate() {
    }

    static String subject(String workflowName) {
        return "Your code to sign \"" + workflowName + "\"";
    }

    static String text(String workflowName, String code, Instant expiresAt, String appName) {
        return "Your verification code is:\n\n"
                + "    " + code + "\n\n"
                + "Enter it in " + appName + " to open the signing request for \""
                + workflowName + "\".\n"
                + "It works once and expires at " + TIME.format(expiresAt) + ".\n\n"
                + "If you did not ask for this code, someone else has your invitation"
                + " link. Do not share the code, and tell the person who sent you the"
                + " document.\n";
    }
}

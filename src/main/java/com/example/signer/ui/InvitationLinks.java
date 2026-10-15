package com.example.signer.ui;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading invitation tokens from the places they arrive: the
 * {@code signflow://invitation/<token>} launch argument, and the query a
 * second launch forwards to the running instance. Pure functions, no UI.
 */
final class InvitationLinks {

    private static final Pattern DEEP_LINK =
            Pattern.compile("signflow://invitation/([A-Za-z0-9_-]{32,})");
    private static final Pattern BARE_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{32,}$");

    private InvitationLinks() {
    }

    /**
     * Extracts the token from the launch arguments. Accepts the full link or
     * a bare token; anything else yields {@code null}.
     */
    static String extractInvitationToken(List<String> rawArgs) {
        for (String arg : rawArgs) {
            if (arg == null) {
                continue;
            }
            Matcher m = DEEP_LINK.matcher(arg);
            if (m.find()) {
                return m.group(1);
            }
            if (BARE_TOKEN.matcher(arg).matches()) {
                return arg;
            }
        }
        return null;
    }

    /**
     * Token forwarded by a second launch as the activation endpoint's
     * {@code token} query parameter, or {@code null}. Same character set as
     * {@link #extractInvitationToken}.
     */
    static String parseActivationToken(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if ("token".equals(key)) {
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                return BARE_TOKEN.matcher(value).matches() ? value : null;
            }
        }
        return null;
    }
}

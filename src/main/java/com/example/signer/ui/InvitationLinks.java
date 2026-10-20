package com.example.signer.ui;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the {@code signflow://} links SignFlow is started with. Pure
 * functions, no UI.
 *
 * <ul>
 *   <li>{@code signflow://invitation/<token>} — an e-mailed invitation
 *       (guest flow).</li>
 *   <li>{@code signflow://launch?v=1&c=<code>} — a one-time launch code from
 *       the website (Phase B). Only version 1 is understood; the code is a
 *       43-character base64url value and nothing else is accepted.</li>
 * </ul>
 *
 * Neither value is ever logged.
 */
public final class InvitationLinks {

    private static final Pattern DEEP_LINK =
            Pattern.compile("signflow://invitation/([A-Za-z0-9_-]{32,})");
    private static final Pattern BARE_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{32,}$");
    /** Exactly one launch link, version 1, nothing before or after it. */
    private static final Pattern LAUNCH_LINK =
            Pattern.compile("^signflow://launch/?\\?v=1&c=([A-Za-z0-9_-]{43})$");
    private static final Pattern LAUNCH_CODE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    /** What a launch argument asks the app to open. */
    public record LaunchArgument(Kind kind, String value) {
        public enum Kind { INVITATION, LAUNCH }

        public LaunchArgument {
            if (kind == null || value == null
                    || !(kind == Kind.LAUNCH ? LAUNCH_CODE : BARE_TOKEN).matcher(value).matches()) {
                throw new IllegalArgumentException("not a valid launch argument");
            }
        }

        /** Never print the secret value. */
        @Override
        public String toString() {
            return "LaunchArgument[" + kind + "]";
        }
    }

    private InvitationLinks() {
    }

    /**
     * The first recognised link in the launch arguments, or {@code null}.
     * A launch link wins over an invitation link when both are present.
     */
    public static LaunchArgument parse(List<String> rawArgs) {
        for (String arg : rawArgs) {
            String code = launchCode(arg);
            if (code != null) {
                return new LaunchArgument(LaunchArgument.Kind.LAUNCH, code);
            }
        }
        String token = extractInvitationToken(rawArgs);
        return token == null ? null : new LaunchArgument(LaunchArgument.Kind.INVITATION, token);
    }

    /** The code of a {@code signflow://launch?v=1&c=…} link, or {@code null}. */
    static String launchCode(String arg) {
        if (arg == null) {
            return null;
        }
        Matcher m = LAUNCH_LINK.matcher(arg.trim());
        return m.matches() ? m.group(1) : null;
    }

    /**
     * Extracts the invitation token from the launch arguments. Accepts the
     * full link or a bare token; anything else yields {@code null}.
     */
    static String extractInvitationToken(List<String> rawArgs) {
        for (String arg : rawArgs) {
            if (arg == null || arg.startsWith("signflow://launch")) {
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
}

package com.example.signer.ui;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the {@code signflow://} links SignFlow is started with. Pure
 * functions, no UI.
 *
 * <p>One link is understood: {@code signflow://launch?v=1&c=<code>} — a
 * one-time launch code from the website. Only version 1 is understood; the
 * code is a 43-character base64url value and nothing else is accepted. This
 * is how the product's signing flow reaches the desktop: sign in on the
 * website, upload a PDF, click Sign, confirm "Open SignFlow".
 *
 * <p>Anything else is not a launch link and {@link #parse} answers
 * {@code null}. {@code signflow://invitation/<token>} was matched here for a
 * while so it could be refused with an explanation, two steps after the
 * invitation workflow itself was removed. That explanation is gone too now:
 * an unrecognised link opens the app the way clicking its icon does.
 *
 * <p>The code is never logged.
 */
public final class LaunchLinks {

    /** Exactly one launch link, version 1, nothing before or after it. */
    private static final Pattern LAUNCH_LINK =
            Pattern.compile("^signflow://launch/?\\?v=1&c=([A-Za-z0-9_-]{43})$");
    private static final Pattern LAUNCH_CODE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    /** What a launch argument asks the app to open. */
    public record LaunchArgument(Kind kind, String value) {

        /**
         * One kind, deliberately still an enum: the hand-off wire format in
         * {@link SingleInstanceGuard} sends the kind as a byte, and the
         * retired INVITATION value (byte 1) must not be silently reused by
         * whatever is added next.
         */
        public enum Kind { LAUNCH }

        public LaunchArgument {
            if (kind == null || value == null || !LAUNCH_CODE.matcher(value).matches()) {
                throw new IllegalArgumentException("not a valid launch argument");
            }
        }

        /** Never print the secret value. */
        @Override
        public String toString() {
            return "LaunchArgument[" + kind + "]";
        }
    }

    private LaunchLinks() {
    }

    /**
     * The first launch link in the launch arguments, or {@code null} when
     * there is none.
     */
    public static LaunchArgument parse(List<String> rawArgs) {
        for (String arg : rawArgs) {
            String code = launchCode(arg);
            if (code != null) {
                return new LaunchArgument(LaunchArgument.Kind.LAUNCH, code);
            }
        }
        return null;
    }

    /** The code of a {@code signflow://launch?v=1&c=…} link, or {@code null}. */
    static String launchCode(String arg) {
        if (arg == null) {
            return null;
        }
        Matcher m = LAUNCH_LINK.matcher(arg.trim());
        return m.matches() ? m.group(1) : null;
    }
}

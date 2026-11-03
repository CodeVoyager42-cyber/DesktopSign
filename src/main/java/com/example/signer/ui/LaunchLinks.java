package com.example.signer.ui;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the {@code signflow://} link SignFlow is started with. Pure
 * functions, no UI.
 *
 * <p>{@code signflow://launch?v=1&c=<code>} — a one-time launch code from
 * the website (Phase B). Only version 1 is understood; the code is a
 * 43-character base64url value and nothing else is accepted. It is never
 * logged.
 */
public final class LaunchLinks {

    /** Exactly one launch link, version 1, nothing before or after it. */
    private static final Pattern LAUNCH_LINK =
            Pattern.compile("^signflow://launch/?\\?v=1&c=([A-Za-z0-9_-]{43})$");
    private static final Pattern LAUNCH_CODE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    /** The one-time code a launch link asks the app to open. */
    public record LaunchArgument(String value) {

        public LaunchArgument {
            if (value == null || !LAUNCH_CODE.matcher(value).matches()) {
                throw new IllegalArgumentException("not a valid launch argument");
            }
        }

        /** Never print the secret value. */
        @Override
        public String toString() {
            return "LaunchArgument[launch]";
        }
    }

    private LaunchLinks() {
    }

    /** The first recognised launch link in the arguments, or {@code null}. */
    public static LaunchArgument parse(List<String> rawArgs) {
        for (String arg : rawArgs) {
            String code = launchCode(arg);
            if (code != null) {
                return new LaunchArgument(code);
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

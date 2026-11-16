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
 * <p>{@code signflow://invitation/<token>} is <b>no longer understood</b>.
 * The e-mail invitation workflow was removed, so such a link can only come
 * from an old e-mail. It is refused rather than ignored: {@link #parse}
 * returns {@code null} for it, and {@link #mentionsRemovedInvitationLink}
 * reports it so the caller can say plainly that invitations are gone instead
 * of starting up as though the user had clicked the icon. A bare token
 * argument is no longer accepted either — there is nothing left to redeem.
 *
 * <p>Neither a code nor a token is ever logged.
 */
public final class InvitationLinks {

    /** Exactly one launch link, version 1, nothing before or after it. */
    private static final Pattern LAUNCH_LINK =
            Pattern.compile("^signflow://launch/?\\?v=1&c=([A-Za-z0-9_-]{43})$");
    private static final Pattern LAUNCH_CODE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    /**
     * A link from the removed invitation workflow. Matched only so it can be
     * refused with an explanation; the token is never read or logged.
     */
    private static final Pattern REMOVED_INVITATION_LINK =
            Pattern.compile("^signflow://invitation/[A-Za-z0-9_-]{32,}$");

    /** What the user is told when they click a link that no longer works. */
    static final String INVITATION_LINK_REMOVED_MESSAGE =
            "This invitation link no longer works. SignFlow does not send e-mail"
                    + " invitations any more. To sign a document, sign in on the SignFlow"
                    + " website, open the document and click \"Sign\" — that opens it here.";

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

    private InvitationLinks() {
    }

    /**
     * The first launch link in the launch arguments, or {@code null} when
     * there is none — including when the only {@code signflow://} argument is
     * an invitation link, which is no longer honoured. Callers that want to
     * explain that case ask {@link #mentionsRemovedInvitationLink}.
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

    /**
     * True when one of the arguments is a link from the removed invitation
     * workflow. Pure, so the refusal is unit-tested without a UI.
     */
    static boolean mentionsRemovedInvitationLink(List<String> rawArgs) {
        for (String arg : rawArgs) {
            if (arg != null && REMOVED_INVITATION_LINK.matcher(arg.trim()).matches()) {
                return true;
            }
        }
        return false;
    }
}

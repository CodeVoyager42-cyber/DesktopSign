package com.example.signer.ui.support;

import com.example.signer.exception.SignerException;
import com.example.signer.token.TokenException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central mapping from technical failures to user-friendly messages (§63).
 * Raw exception details stay in the developer logs; this class produces the
 * single professional sentence the UI shows.
 */
public final class ErrorMessages {

    private static final Logger log = LoggerFactory.getLogger(ErrorMessages.class);

    /** Shown for failures that carry no application-authored message. */
    static final String UNEXPECTED = "Something unexpected went wrong."
            + " The details were written to the application log.";

    private ErrorMessages() {
    }

    /** Friendly message for a token failure, by error code. */
    public static String token(TokenException e) {
        return switch (e.getErrorCode()) {
            case TOKEN_NOT_CONNECTED, TOKEN_REMOVED ->
                    "The signing token is not connected.\n"
                            + "Please connect the signer's USB token and try again.";
            case TOKEN_PIN_INCORRECT ->
                    "The PIN is incorrect. Please try again.";
            case TOKEN_PIN_LOCKED ->
                    "The token is locked after too many incorrect PIN attempts.\n"
                            + "Contact your token administrator to unlock it.";
            case TOKEN_LOGIN_FAILED ->
                    "The token did not accept the authentication.\n"
                            + "Check the PIN and try again.";
            case PRIVATE_KEY_NOT_AVAILABLE ->
                    "No compatible signing certificate was found on this token.";
            case UNSUPPORTED_ALGORITHM ->
                    "The token does not support the requested signature algorithm.";
            case TOKEN_SESSION_FAILED ->
                    "The token session failed. Reconnect the token and try again.";
            default -> e.getMessage() == null
                    ? "An unexpected token error occurred."
                    : e.getMessage();
        };
    }

    /**
     * Friendly message for any throwable (last resort, never exposes secrets).
     *
     * <p>Only messages written by SignFlow itself ({@link SignerException},
     * which also covers the coordination server's error texts) are shown.
     * Anything else — library or JDK exceptions such as an
     * {@code IOException} or a {@code NullPointerException} — would leak
     * class names and internals, so the user sees one plain sentence and
     * the technical detail goes to the log.
     */
    public static String generic(Throwable t) {
        if (t == null) {
            return "An unexpected error occurred.";
        }
        if (t instanceof TokenException te) {
            return token(te);
        }
        if (t instanceof SignerException se) {
            String message = se.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        log.warn("Unexpected error shown to the user as a generic message", t);
        return UNEXPECTED;
    }
}

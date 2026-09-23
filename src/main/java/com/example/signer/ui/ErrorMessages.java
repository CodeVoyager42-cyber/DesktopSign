package com.example.signer.ui;

import com.example.signer.token.TokenException;

/**
 * Central mapping from technical failures to user-friendly messages (§63).
 * Raw exception details stay in the developer logs; this class produces the
 * single professional sentence the UI shows.
 */
public final class ErrorMessages {

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

    /** Friendly message for any throwable (last-resort, never exposes secrets). */
    public static String generic(Throwable t) {
        if (t == null) {
            return "An unexpected error occurred.";
        }
        if (t instanceof TokenException te) {
            return token(te);
        }
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : message;
    }
}

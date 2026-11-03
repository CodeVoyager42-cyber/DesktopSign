package com.example.signer.token;

/**
 * Application-level token state.
 *
 * <p>Owned by {@link TokenService} and driven by the strict transitions defined
 * in {@link TokenStateMachine}. Covers the full lifecycle from "no token
 * present" up to a completed (or failed) signature operation.
 */
public enum TokenState {

    /** No token detected. */
    NO_TOKEN,

    /** A token is physically present. */
    TOKEN_DETECTED,

    /** The token session has been initialized and metadata is readable. */
    TOKEN_INITIALIZED,

    /** Certificates have been enumerated and are available for selection. */
    CERTIFICATE_AVAILABLE,

    /** The user must authenticate with the token PIN. */
    WAITING_FOR_PIN,

    /** The user authenticated and the token session is logged in. */
    AUTHENTICATED,

    /** A signing operation is in progress. */
    SIGNING,

    /** The last signing operation completed successfully. */
    SIGNATURE_COMPLETE,

    /** The last signing operation failed. */
    SIGNATURE_FAILED,

    /** The token was removed and the application is waiting for re-insertion. */
    TOKEN_REMOVED
}
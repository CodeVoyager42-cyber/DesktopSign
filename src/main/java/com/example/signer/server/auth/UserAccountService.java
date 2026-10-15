package com.example.signer.server.auth;

/**
 * Credential verification seam (brief Phase 2/§45): the workflow API
 * authenticates against fixed configuration accounts (legacy/dev mode) or
 * against database-backed accounts (bcrypt, per-user, email capable) when
 * {@code signature.remote-server.database.enabled=true}.
 *
 * <p>Implementations must never log passwords or hashes and must return the
 * same negative answer for unknown user and wrong password.
 */
public interface UserAccountService {

    /** Whether the credentials are valid. Constant-time comparisons only. */
    boolean matches(String username, String password);

    /**
     * The canonical ACCOUNT id behind a login identifier. A user may sign in
     * with an email or a username, but the authenticated identity — the JWT
     * subject and every workflow lookup — is the account id, never the typed
     * string. Only called after {@link #matches} succeeded.
     */
    default String accountId(String usernameOrEmail) {
        return usernameOrEmail == null ? null : usernameOrEmail.trim();
    }

    /** Whether the user id is known (login eligible or invited placeholder). */
    boolean knowsUser(String username);

    /**
     * Whether {@code username} belongs to a REGISTERED account (it can
     * authenticate). Invited placeholders answer {@code false}: they exist
     * only as the identity an invitation is bound to, and must never be
     * presented as an account, an authenticated user or a signer.
     *
     * <p>File-mode accounts are always registered.
     */
    default boolean isRegistered(String username) {
        return true;
    }

    /** Called after a successful login so implementations can bookkeep. */
    default void onLoginSuccess(String username) {
    }
}

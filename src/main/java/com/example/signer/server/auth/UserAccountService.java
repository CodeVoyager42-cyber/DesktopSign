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

    /**
     * Whether the account may take <b>signing-related</b> actions — creating
     * a signing request, or opening a signing session (security Phase D
     * decision). An account whose e-mail address is not verified may sign in
     * and look around, but not this: everything here either commits the
     * account's name to a document or sends e-mail in its name.
     *
     * <p>File-mode accounts have no address to verify, so they always may.
     * The check lives behind this seam rather than in each caller so the
     * desktop API and the website are gated by the same rule.
     */
    default boolean maySign(String username) {
        return true;
    }

    /**
     * The non-secret profile of an account, for the website's {@code /me}
     * (security Phase C). Implementations must never put a password, a hash,
     * a token or any workflow data in it.
     *
     * <p>File-mode accounts have no e-mail, so the default reports none and
     * reports the address as unverified.
     */
    default AccountProfile profile(String username) {
        return new AccountProfile(username, null, false);
    }

    /**
     * What a signed-in visitor may learn about their own account. Nothing
     * here is a credential and nothing here identifies another account.
     */
    record AccountProfile(String username, String email, boolean emailVerified) {
    }
}

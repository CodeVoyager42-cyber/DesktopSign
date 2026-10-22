package com.example.signer.server.auth;

import java.time.Instant;

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
     * Whether the account may take <b>signing-related</b> actions (security
     * Phase D decision). An account whose e-mail address is not verified may
     * sign in and look around, but not this: everything gated on it either
     * commits the account's name to a document or opens a session that can.
     *
     * <p>File-mode accounts have no address to verify, so they always may.
     * The check lives behind this seam rather than in each caller so that
     * every path is gated by one rule. The callers are:
     *
     * <ul>
     *   <li>{@code RemoteWorkflowService.createWorkflow} — starting a signing
     *       request, for every caller path including the website;</li>
     *   <li>{@code AuthenticatedRequestFilter}, for the account-token
     *       requests in {@link SigningActionPolicy} — claiming a turn,
     *       submitting a signature, binding a certificate, issuing a launch
     *       code;</li>
     *   <li>{@code LaunchCodeService} — issuing a launch code, exchanging one
     *       for a task session, and re-checking that session on every
     *       request.</li>
     * </ul>
     *
     * <p>It does <b>not</b> gate guest signing sessions: a guest has no
     * account, and proves control of its address by receiving an e-mailed
     * code instead.
     */
    default boolean maySign(String username) {
        return true;
    }

    /**
     * Whether the account's password changed <b>after</b> a token was issued
     * (security Phase D1 risk D7) — in which case that token belongs to the
     * session the password change was meant to end.
     *
     * <p>A reset or a change already revokes every website session and every
     * refresh token, but an access token is a signed JWT: nothing is stored
     * for it, so nothing can be deleted, and it stayed usable for the rest of
     * its life. This is what closes that window, and it costs nothing extra
     * to check: {@code AuthenticatedRequestFilter} already loads the account
     * on every request to see whether it can still sign in at all.
     *
     * <p>Compared at the token's own resolution — {@code iat} is whole
     * seconds — rather than the column's microseconds. Otherwise a sign-in in
     * the same second as the change would be refused its brand new token,
     * which is precisely the "reset, then sign in again" path this is here to
     * serve. The cost is that a token issued within the same second as the
     * change survives; the benefit is that the common flow works.
     *
     * <p>File-mode passwords are configuration and never change at runtime,
     * so the default is {@code false}.
     *
     * @param tokenIssuedAt the token's {@code iat}, which every token this
     *                      server issues carries and
     *                      {@code JwtService.lifetimeValidator} requires
     */
    default boolean passwordChangedSince(String username, Instant tokenIssuedAt) {
        return false;
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

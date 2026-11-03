package com.example.signer.server.auth;

import java.time.Instant;

/**
 * Access tokens that have been signed out of (security Phase 2 risk L4).
 *
 * <p>A website session is a row and a refresh token is a row, so signing out
 * deletes something. An access token is a <b>signed JWT</b>: nothing is
 * stored for it, so until this existed {@code /api/auth/logout} ended the
 * refresh session and the access token kept working for the rest of its 15
 * minutes. Phase 2 recorded that as risk L4 and named the fix — a short-lived
 * {@code jti} deny-list. This is it.
 *
 * <p>Only the token's {@code jti} is kept, and only until the token would
 * have expired on its own. That bounds the list by how many sign-outs happen
 * inside one access-token lifetime, and it means the list can never be the
 * thing that keeps a token alive: expiry is still enforced by the decoder,
 * and a swept row only removes an entry whose token was already dead.
 *
 * <p>Two implementations, chosen by the same property that decides whether
 * there is a database at all — exactly as {@link RefreshTokenStore} is:
 *
 * <ul>
 *   <li>{@link DbAccessTokenDenyList} — {@code denied_access_tokens} (V12).
 *       One list across every instance, and it survives a restart.</li>
 *   <li>{@link InMemoryAccessTokenDenyList} — a map on the heap, for a server
 *       running without a database. Per instance, and forgotten on restart.
 *       The residual half of this risk, for the same reason it is the
 *       residual half of D3.</li>
 * </ul>
 *
 * <p><b>Why a deny-list and not a stamp on the account.</b> Risk D7 ends
 * every bearer token of an account by comparing {@code iat} against
 * {@code password_changed_at}, which is one column and no new table — but it
 * ends <i>every</i> token of that account. Signing out of one device must not
 * sign out the others, so a logout cannot reuse that mechanism; it has to
 * name the single token it is ending.
 */
public interface AccessTokenDenyList {

    /**
     * Refuses {@code tokenId} from now until {@code expiresAt}. Idempotent:
     * denying the same id twice is one entry, so a client that posts its
     * logout twice is not an error.
     *
     * <p>A token that has already expired is <b>not</b> recorded: there is
     * nothing to deny, because the decoder refuses it before any of this is
     * reached.
     *
     * @param tokenId   the token's {@code jti} claim; ignored when absent
     * @param expiresAt the token's own {@code exp} — never a time of this
     *                  method's choosing, so the entry cannot outlive what
     *                  it is denying
     */
    void deny(String tokenId, Instant expiresAt);

    /**
     * Whether {@code tokenId} has been signed out of. Called on every bearer
     * request that carries an account access token, which is why the
     * database implementation's lookup is a single index hit on a unique
     * column.
     */
    boolean isDenied(String tokenId);

    /**
     * The longest {@code jti} this list can hold. The ids it is given are
     * UUIDs from {@code JwtService}, 36 characters, so the limit exists only
     * so that a longer one is refused loudly rather than silently truncated
     * into something that would never match — a deny-list that stored a
     * mangled id would believe it had revoked a token it had not.
     */
    int MAX_TOKEN_ID_LENGTH = 64;
}

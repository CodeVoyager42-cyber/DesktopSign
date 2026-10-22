package com.example.signer.server.auth;

import com.example.signer.server.db.repository.DeniedTokenRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * The deny-list in PostgreSQL ({@code denied_access_tokens}, V12): one list
 * shared by every instance using the same database, and it survives a
 * restart.
 *
 * <p>That sharing is the point, in the same way it was the point for the rate
 * limits (risk D3). A logout reaches exactly one instance. With the list on
 * that instance's heap, the token it just revoked would still be accepted by
 * every other instance for the rest of its life — a load balancer would
 * decide whether signing out worked.
 *
 * <h2>It fails closed</h2>
 *
 * <p>Deliberately the opposite choice from {@link
 * com.example.signer.server.security.DbRateLimitStore}, and for a reason that
 * is specific rather than a matter of taste. A rate limit is an availability
 * guard, so allowing an uncounted attempt when the database is unreachable is
 * the lesser harm. <b>This is a revocation check</b>: a revocation check that
 * answers "not revoked" when it does not know is not a revocation check, and
 * an attacker able to provoke database errors could re-enable every token
 * that had been signed out of.
 *
 * <p>Failing closed costs nothing here, which is what makes it an easy
 * choice. {@code AuthenticatedRequestFilter} has already read the account
 * from this same database — twice, for risks D4 and D7 — before it asks this
 * question, so a database that cannot answer it could not have got the
 * request this far either. A failure is therefore left to propagate instead
 * of being swallowed into a {@code false}.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class DbAccessTokenDenyList implements AccessTokenDenyList {

    private static final Logger log = LoggerFactory.getLogger(DbAccessTokenDenyList.class);

    /**
     * How long after a token's own expiry its row is kept. The row is worth
     * nothing the moment the token expires — the decoder refuses the token
     * without any help from this table — so this grace exists only so that a
     * modest clock difference between instances cannot let one of them sweep
     * a row another still considers live.
     */
    private static final Duration RETENTION = Duration.ofHours(1);

    private final DeniedTokenRepository denied;

    public DbAccessTokenDenyList(DeniedTokenRepository denied) {
        this.denied = denied;
    }

    @Override
    @Transactional
    public void deny(String tokenId, Instant expiresAt) {
        if (tokenId == null || tokenId.isBlank() || expiresAt == null) {
            return;
        }
        if (tokenId.length() > MAX_TOKEN_ID_LENGTH) {
            // Unreachable for a token this server issued (a 36-character
            // UUID), and never silently truncated — see MAX_TOKEN_ID_LENGTH.
            log.warn("Not denying an access token: its id is {} characters, over the {}"
                    + " the column holds", tokenId.length(), MAX_TOKEN_ID_LENGTH);
            return;
        }
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) {
            // Nothing to deny, and the only case the table's CHECK would
            // have refused: expires_at must be after denied_at.
            return;
        }
        this.denied.deny(tokenId, now, expiresAt);
    }

    @Override
    public boolean isDenied(String tokenId) {
        return tokenId != null && this.denied.existsByTokenId(tokenId);
    }

    /**
     * Hourly clean-up of rows whose tokens expired more than
     * {@link #RETENTION} ago. Nothing depends on it for safety: an expired
     * token is refused by the decoder, so a row that outstays its welcome
     * denies something that was already dead. It exists because the table
     * would otherwise only ever grow, one row per sign-out for the life of
     * the deployment.
     *
     * <p>The initial delay is offset from the other sweeps (refresh tokens at
     * 10 min, launch codes at 15, account tokens at 20, rate-limit counters
     * at 25, accounts at 30) so they do not all run at once on start-up.
     */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 2_100_000)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteExpired() {
        int removed = this.denied.deleteExpiredBefore(Instant.now().minus(RETENTION));
        if (removed > 0) {
            // A count, never an id.
            log.info("Deleted {} expired deny-list row(s)", removed);
        }
    }
}

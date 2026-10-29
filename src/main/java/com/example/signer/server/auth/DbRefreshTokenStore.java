package com.example.signer.server.auth;

import com.example.signer.server.db.entity.RefreshTokenEntity;
import com.example.signer.server.db.repository.RefreshTokenRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Database-mode refresh sessions (V6 {@code refresh_tokens}): they survive
 * server restarts and are shared by every server instance using the same
 * database.
 *
 * <p>Rotation locks the token's row ({@code SELECT … FOR UPDATE}) for the
 * duration of the transaction, so a concurrent second refresh of the same
 * token waits, then finds it rotated and is treated as reuse.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class DbRefreshTokenStore implements RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(DbRefreshTokenStore.class);

    /** Expired rows are kept this long (for reuse detection), then deleted. */
    private static final Duration RETENTION = Duration.ofDays(7);

    private final RefreshTokenRepository tokens;
    private final SecureRandom random = new SecureRandom();
    private final Duration refreshTtl;

    public DbRefreshTokenStore(RefreshTokenRepository tokens,
            @Value("${signature.remote-server.refresh-token-hours:12}") long refreshHours) {
        this.tokens = tokens;
        this.refreshTtl = Duration.ofHours(refreshHours);
    }

    @Override
    @Transactional
    public String issue(String userId) {
        return store(userId, UUID.randomUUID());
    }

    /*
     * A detected reuse must COMMIT the family revocation. It does, because
     * ReusedDetected is a return value, not an exception that rolls back.
     */
    @Override
    @Transactional
    public RefreshResult refresh(String token) {
        if (token == null || token.isBlank()) {
            return new RefreshResult.Invalid();
        }
        RefreshTokenEntity current = tokens.lockByTokenHash(RefreshTokenStore.hash(token))
                .orElse(null);
        if (current == null || current.getRevokedAt() != null) {
            return new RefreshResult.Invalid();
        }
        Instant now = Instant.now();
        if (current.getRotatedAt() != null) {
            log.warn("Refresh-token reuse detected: revoking session family {} of user {}",
                    current.getFamilyId(), current.getUsername());
            tokens.revokeFamily(current.getFamilyId(), now);
            return new RefreshResult.ReusedDetected();
        }
        if (!now.isBefore(current.getExpiresAt())) {
            return new RefreshResult.Invalid();
        }
        current.setRotatedAt(now);
        tokens.save(current);
        String next = store(current.getUsername(), current.getFamilyId());
        return new RefreshResult.Accepted(current.getUsername(), next);
    }

    @Override
    @Transactional
    public boolean revoke(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return tokens.findByTokenHash(RefreshTokenStore.hash(token))
                .filter(t -> t.getRevokedAt() == null)
                .map(t -> tokens.revokeFamily(t.getFamilyId(), Instant.now()) > 0)
                .orElse(false);
    }

    @Override
    @Transactional
    public void revokeAllForUser(String userId) {
        if (userId != null) {
            tokens.revokeAllForUser(userId, Instant.now());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isUsable(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        return tokens.findByTokenHash(RefreshTokenStore.hash(token))
                .filter(t -> t.getRevokedAt() == null && t.getRotatedAt() == null
                        && now.isBefore(t.getExpiresAt()))
                .isPresent();
    }

    /** Hourly clean-up of rows expired more than {@link #RETENTION} ago. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteExpired() {
        int removed = tokens.deleteExpiredBefore(Instant.now().minus(RETENTION));
        if (removed > 0) {
            log.info("Deleted {} expired refresh token row(s)", removed);
        }
    }

    private String store(String userId, UUID family) {
        String token = RefreshTokenStore.randomToken(random);
        Instant now = Instant.now();
        tokens.save(new RefreshTokenEntity(RefreshTokenStore.hash(token), userId, family,
                now, now.plus(refreshTtl)));
        return token;
    }
}

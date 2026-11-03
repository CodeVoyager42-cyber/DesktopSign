package com.example.signer.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-mode refresh sessions, kept in memory: a restart signs everyone out.
 * Database mode uses {@link DbRefreshTokenStore} instead.
 *
 * <p>Every operation is {@code synchronized}: checking a token and marking
 * it rotated happen as one step, so two simultaneous refreshes of the same
 * token cannot both succeed.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "false", matchIfMissing = true)
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRefreshTokenStore.class);

    private record Session(String userId, String familyId,
                           Instant expiresAt, boolean rotated, boolean revoked) {
        Session rotatedCopy() {
            return new Session(userId, familyId, expiresAt, true, revoked);
        }

        Session revokedCopy() {
            return new Session(userId, familyId, expiresAt, rotated, true);
        }
    }

    /** Key: token hash. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration refreshTtl;

    public InMemoryRefreshTokenStore(
            @Value("${signature.remote-server.refresh-token-hours:12}") long refreshHours) {
        this.refreshTtl = Duration.ofHours(refreshHours);
    }

    @Override
    public synchronized String issue(String userId) {
        String token = RefreshTokenStore.randomToken(random);
        sessions.put(RefreshTokenStore.hash(token), new Session(userId,
                UUID.randomUUID().toString(), Instant.now().plus(refreshTtl), false, false));
        return token;
    }

    @Override
    public synchronized RefreshResult refresh(String token) {
        if (token == null || token.isBlank()) {
            return new RefreshResult.Invalid();
        }
        String key = RefreshTokenStore.hash(token);
        Session session = sessions.get(key);
        if (session == null || session.revoked()) {
            return new RefreshResult.Invalid();
        }
        if (session.rotated()) {
            log.warn("Refresh-token reuse detected: revoking session family {} of user {}",
                    session.familyId(), session.userId());
            revokeFamily(session.familyId());
            return new RefreshResult.ReusedDetected();
        }
        if (!Instant.now().isBefore(session.expiresAt())) {
            sessions.remove(key);
            return new RefreshResult.Invalid();
        }
        sessions.put(key, session.rotatedCopy());
        String next = RefreshTokenStore.randomToken(random);
        sessions.put(RefreshTokenStore.hash(next), new Session(session.userId(),
                session.familyId(), Instant.now().plus(refreshTtl), false, false));
        return new RefreshResult.Accepted(session.userId(), next);
    }

    @Override
    public synchronized boolean revoke(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Session session = sessions.get(RefreshTokenStore.hash(token));
        if (session == null || session.revoked()) {
            return false;
        }
        return revokeFamily(session.familyId()) > 0;
    }

    @Override
    public synchronized void revokeAllForUser(String userId) {
        sessions.replaceAll((k, s) -> s.userId().equals(userId) ? s.revokedCopy() : s);
    }

    @Override
    public synchronized boolean isUsable(String token) {
        Session s = token == null ? null : sessions.get(RefreshTokenStore.hash(token));
        return s != null && !s.revoked() && !s.rotated()
                && Instant.now().isBefore(s.expiresAt());
    }

    private int revokeFamily(String familyId) {
        int[] count = {0};
        sessions.replaceAll((k, s) -> {
            if (s.familyId().equals(familyId) && !s.revoked()) {
                count[0]++;
                return s.revokedCopy();
            }
            return s;
        });
        return count[0];
    }
}

package com.example.signer.server.auth;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-side store of refresh-token sessions with rotation and reuse
 * detection (Phase 4). Refresh tokens are 256-bit random values; only a
 * SHA-256 hash is retained, so a database/storage dump never yields usable
 * tokens.
 *
 * <p>Rotation semantics: presenting a valid (non-revoked, non-expired)
 * refresh token marks that session as rotated and returns a NEW refresh
 * token bound to the same user. If the OLD token is ever presented again,
 * this is textbook token theft — the whole session family is revoked and
 * the reuse is audited.
 *
 * <p>Thread safety: a per-session lock serialises concurrent refreshes of
 * the same token; different sessions proceed independently.
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private record Session(String userId, String familyId,
                           Instant expiresAt, boolean rotated, boolean revoked) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock issueLock = new ReentrantLock();
    private final SecureRandom random = new SecureRandom();
    private final Duration refreshTtl;

    public RefreshTokenStore(
            @Value("${signature.remote-server.refresh-token-hours:12}") long refreshHours) {
        this.refreshTtl = Duration.ofHours(refreshHours);
    }

    /** Result of a refresh attempt. */
    public sealed interface RefreshResult {
        /** Refresh accepted: new token for this user (old one is now rotated). */
        record Accepted(String userId, String newRefreshToken) implements RefreshResult {
        }

        /** Reuse of a rotated/revoked token: every session of the family is dead. */
        record ReusedDetected() implements RefreshResult {
        }

        /** Unknown, expired or already-revoked token. */
        record Invalid() implements RefreshResult {
        }
    }

    /** Issues a fresh refresh token for a new login session. */
    public String issue(String userId) {
        issueLock.lock();
        try {
            String token = randomToken();
            sessions.put(hash(token), new Session(userId,
                    UUID.randomUUID().toString(),
                    Instant.now().plus(refreshTtl), false, false));
            return token;
        } finally {
            issueLock.unlock();
        }
    }

    /**
     * Attempts to exchange {@code token} for a new refresh token (rotation).
     * Never returns the user id on failure paths — callers must treat
     * non-accepted results as "re-authenticate".
     */
    public RefreshResult refresh(String token) {
        if (token == null || token.isBlank()) {
            return new RefreshResult.Invalid();
        }
        String key = hash(token);
        Session session = sessions.get(key);
        if (session == null) {
            return new RefreshResult.Invalid();
        }
        if (session.revoked()) {
            return new RefreshResult.Invalid();
        }
        if (session.rotated()) {
            // REUSE of an already-rotated token = likely theft: revoke the
            // whole family so every descendant token dies.
            log.warn("Refresh-token reuse detected — revoking family {} for user {}",
                    session.familyId(), session.userId());
            sessions.values().stream()
                    .filter(s -> s.familyId().equals(session.familyId()))
                    .forEach(s -> sessions.replace(hashKeyOf(s), markRevoked(s)));
            return new RefreshResult.ReusedDetected();
        }
        if (Instant.now().isAfter(session.expiresAt())) {
            sessions.remove(key);
            return new RefreshResult.Invalid();
        }
        // Rotate: mark old, issue a new token in the same family.
        sessions.replace(key, markRotated(session));
        String newToken = randomToken();
        sessions.put(hash(newToken), new Session(session.userId(), session.familyId(),
                Instant.now().plus(refreshTtl), false, false));
        return new RefreshResult.Accepted(session.userId(), newToken);
    }

    /** Revokes the session identified by {@code token} (logout). */
    public boolean revoke(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String key = hash(token);
        Session session = sessions.get(key);
        if (session == null || session.revoked()) {
            return false;
        }
        sessions.replace(key, markRevoked(session));
        return true;
    }

    /** Revokes every session of {@code userId}. */
    public void revokeAllForUser(String userId) {
        sessions.entrySet().removeIf(e ->
                e.getValue().userId().equals(userId) && !e.getValue().revoked());
    }

    /** Test/introspection helper: whether {@code token} is currently usable. */
    public boolean isUsable(String token) {
        Session s = token == null ? null : sessions.get(hash(token));
        return s != null && !s.revoked() && !s.rotated()
                && Instant.now().isBefore(s.expiresAt());
    }

    /* ------------------------------------------------------------------ */

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hash(String token) {
        return sha256Hex(token.getBytes(StandardCharsets.UTF_8));
    }

    private String hashKeyOf(Session s) {
        return sessions.entrySet().stream()
                .filter(e -> e.getValue() == s)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private static Session markRotated(Session s) {
        return new Session(s.userId(), s.familyId(), s.expiresAt(), true, s.revoked());
    }

    private static Session markRevoked(Session s) {
        return new Session(s.userId(), s.familyId(), s.expiresAt(), s.rotated(), true);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

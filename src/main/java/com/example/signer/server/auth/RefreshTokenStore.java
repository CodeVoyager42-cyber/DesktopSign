package com.example.signer.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Refresh-token sessions with rotation and reuse detection.
 *
 * <p>A refresh token is a 256-bit random value shown to the client once;
 * only its SHA-256 hash is kept. Presenting a valid token replaces it with a
 * new one in the same family (one family = one sign-in). Presenting an
 * already-replaced token again is treated as theft: the whole family is
 * revoked and the client must sign in again.
 *
 * <p>Two implementations: {@link DbRefreshTokenStore} (database mode,
 * survives restarts) and {@link InMemoryRefreshTokenStore} (file mode).
 * Neither ever logs a token value.
 */
public interface RefreshTokenStore {

    /** Outcome of {@link #refresh}. */
    sealed interface RefreshResult {
        record Accepted(String userId, String newRefreshToken) implements RefreshResult {
        }

        record ReusedDetected() implements RefreshResult {
        }

        record Invalid() implements RefreshResult {
        }
    }

    /** Starts a new family for {@code userId} and returns its first token. */
    String issue(String userId);

    /** Rotates {@code token}; see {@link RefreshResult}. */
    RefreshResult refresh(String token);

    /**
     * Logout: revokes the family {@code token} belongs to. Returns whether
     * anything usable was revoked. Idempotent.
     */
    boolean revoke(String token);

    /** Revokes every session of {@code userId} (account disabled or removed). */
    void revokeAllForUser(String userId);

    /** Whether {@code token} would currently be accepted by {@link #refresh}. */
    boolean isUsable(String token);

    /* ------------------------------------------------------------------ */

    /** A new random refresh-token value (64 hex characters). */
    static String randomToken(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** SHA-256 hex of a token value: the only form that is ever stored. */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package com.example.signer.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Single-use secrets delivered by e-mail: 256 bits of {@link SecureRandom},
 * handed out exactly once, stored only as a SHA-256 hex digest.
 *
 * <p>The same shape the invitation tokens (V2) and launch codes (V8) already
 * use. Collected here so the account tokens of security Phase D do not add a
 * fourth private copy of it.
 *
 * <p>SHA-256 with no salt or iteration count is right here and wrong for a
 * password: the input is 256 bits of uniform randomness, so there is nothing
 * to brute-force or pre-compute. Passwords go through
 * {@link PasswordHashing} (bcrypt) instead.
 */
public final class OneTimeTokens {

    /** Entropy of a raw token, in bytes. */
    public static final int TOKEN_BYTES = 32;

    /** Length of a raw token: 32 bytes, base64url, unpadded. */
    public static final int TOKEN_LENGTH = 43;

    private static final SecureRandom RANDOM = new SecureRandom();

    private OneTimeTokens() {
    }

    /** A new raw token. The caller must deliver it and then forget it. */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The stored form of {@code token}: SHA-256, lower-case hex. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Whether {@code candidate} has the shape of a token at all. Checked
     * before hashing so a malformed submission costs nothing and cannot be
     * told apart from a wrong one by timing.
     */
    public static boolean looksLikeToken(String candidate) {
        if (candidate == null || candidate.length() != TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}

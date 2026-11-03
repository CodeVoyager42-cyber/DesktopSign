package com.example.signer.server.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;

/**
 * The single place passwords are hashed and verified (security Phase 1).
 *
 * <p>BCrypt via Spring Security's {@link BCryptPasswordEncoder}, cost
 * {@value #COST}. Existing hashes made with a lower cost (the previous
 * default was 10) still verify; {@link #needsRehash} tells the caller to
 * store a fresh cost-{@value #COST} hash after a successful login.
 *
 * <p>BCrypt only reads the first 72 bytes of its input, so longer
 * passwords would silently be truncated. {@link #validateNewPassword}
 * rejects them instead. Verification does not apply that limit, so
 * passwords that were accepted before keep working.
 *
 * <p>Never log the password, the hash, or the exception message of a
 * failed check.
 */
public final class PasswordHashing {

    /** BCrypt work factor for new hashes. */
    public static final int COST = 12;
    /** Minimum password length, in characters. */
    public static final int MIN_LENGTH = 8;
    /** BCrypt input limit, in UTF-8 bytes. */
    public static final int MAX_BYTES = 72;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(COST);

    /**
     * Hash compared against when the account does not exist or cannot sign
     * in, so those answers take as long as a wrong password. Created once,
     * from a random value nobody knows.
     */
    private static final String DUMMY_HASH =
            ENCODER.encode(java.util.UUID.randomUUID().toString());

    private PasswordHashing() {
    }

    /**
     * Why a new password is unacceptable, or {@code null} when it is fine.
     * The message never contains the password.
     */
    public static String validateNewPassword(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "The password must be at least " + MIN_LENGTH + " characters";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "The password must be at most " + MAX_BYTES
                    + " bytes (about " + MAX_BYTES + " plain letters or digits)";
        }
        return null;
    }

    /**
     * A new cost-{@value #COST} hash. Refuses a null password or one over
     * {@value #MAX_BYTES} bytes (it would be truncated). The length minimum is
     * a registration rule, checked by {@link #validateNewPassword}.
     */
    public static String hash(String password) {
        if (password == null) {
            throw new IllegalArgumentException("A password is required");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "The password is longer than " + MAX_BYTES + " bytes");
        }
        return ENCODER.encode(password);
    }

    /**
     * Whether {@code password} matches {@code storedHash}. A missing or
     * malformed hash answers {@code false}, after the same amount of work as
     * a real comparison.
     */
    public static boolean matches(String password, String storedHash) {
        if (password == null) {
            ENCODER.matches("", DUMMY_HASH);
            return false;
        }
        if (!isBcrypt(storedHash)) {
            ENCODER.matches(password, DUMMY_HASH);
            return false;
        }
        try {
            return ENCODER.matches(password, storedHash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Burns the same time as a real check; for unknown or blocked accounts. */
    public static void simulateCheck(String password) {
        ENCODER.matches(password == null ? "" : password, DUMMY_HASH);
    }

    /** Whether a verified hash should be replaced by a fresh cost-{@value #COST} one. */
    public static boolean needsRehash(String storedHash) {
        return isBcrypt(storedHash) && ENCODER.upgradeEncoding(storedHash);
    }

    /** Whether the value looks like a BCrypt hash ({@code $2a$}, {@code $2b$}, {@code $2y$}). */
    public static boolean isBcrypt(String value) {
        return value != null && value.length() == 60
                && value.matches("\\$2[aby]?\\$\\d\\d\\$[./A-Za-z0-9]{53}");
    }
}

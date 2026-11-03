package com.example.signer.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Helpers for writing user-supplied values to the log without leaking or forging them. */
public final class LogSafe {

    private LogSafe() {
    }

    /**
     * A short, stable fingerprint of a typed identifier: the same input
     * always gives the same 12 hex characters, so repeated failures can be
     * correlated, but the text itself never reaches the log.
     */
    public static String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "id#" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

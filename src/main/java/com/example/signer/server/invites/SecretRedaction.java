package com.example.signer.server.invites;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log-safe rendering of text that may contain bearer secrets (invitation
 * tokens inside links). A long URL-safe token is replaced by a short SHA-256
 * prefix: enough to correlate log lines with the stored token hash, useless
 * to an attacker reading the log.
 */
public final class SecretRedaction {

    /** Invitation tokens are 32-byte base64url values (43 chars); be generous. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{24,}");

    private SecretRedaction() {
    }

    /** Short, non-reversible identifier of {@code secret}: {@code sha256:1a2b3c4d}. */
    public static String fingerprint(String secret) {
        if (secret == null) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code text} with every token-like value replaced by its fingerprint. */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(
                    "<redacted " + fingerprint(m.group()) + ">"));
        }
        m.appendTail(out);
        return out.toString();
    }
}

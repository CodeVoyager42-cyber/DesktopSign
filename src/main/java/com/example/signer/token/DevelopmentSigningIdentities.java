package com.example.signer.token;

import java.util.Locale;
import java.util.Set;

/**
 * Recognises signing identities that must never be trusted outside
 * development: certificates produced by the development mock token.
 *
 * <ul>
 *   <li>Current mock certificates carry the marker organisational unit
 *       {@link #MOCK_MARKER_OU} in their subject.</li>
 *   <li>Builds before the P0 security fix derived the mock keys from
 *       PUBLIC seeds in the source code, so every installation shared the
 *       same three private keys. Their certificate fingerprints are listed
 *       in {@link #LEGACY_SHARED_FINGERPRINTS}: anyone can still recreate
 *       those keys, so a production server must reject them forever.</li>
 * </ul>
 */
public final class DevelopmentSigningIdentities {

    /** Organisational unit placed in every mock certificate subject. */
    public static final String MOCK_MARKER_OU = "SignFlow DEVELOPMENT MOCK TOKEN - NOT FOR PRODUCTION";

    /**
     * SHA-256 fingerprints (upper-case hex, no colons) of the publicly
     * derivable mock certificates shipped by builds before the fix
     * (aliases mock-signing-key, mock-third-signing-key, mock-ec-signing-key).
     */
    public static final Set<String> LEGACY_SHARED_FINGERPRINTS = Set.of(
            "9C63EEDBFBAE3F9EE58DE7EABE3366AFF8DA544826CAB4AD8D38BB33A845542D",
            "15B72B89E54339A9DF3B9F6746943A089EC8FC2F391C5B6C8898DF64D7FE9E02",
            "AED670E077BC39A8454D5B2C9624A1265A1AE6512306CC2169A92B32500E8D51");

    private DevelopmentSigningIdentities() {
    }

    /** Whether {@code fingerprint} is one of the publicly known legacy mock keys. */
    public static boolean isLegacySharedFingerprint(String fingerprint) {
        return fingerprint != null
                && LEGACY_SHARED_FINGERPRINTS.contains(normalize(fingerprint));
    }

    /**
     * Whether a signing certificate is a development identity: either it
     * carries the mock marker in its subject, or its fingerprint is a legacy
     * publicly derivable mock key.
     */
    public static boolean isDevelopmentIdentity(String subjectDn, String fingerprint) {
        if (isLegacySharedFingerprint(fingerprint)) {
            return true;
        }
        return subjectDn != null
                && subjectDn.toUpperCase(Locale.ROOT)
                        .contains(MOCK_MARKER_OU.toUpperCase(Locale.ROOT));
    }

    private static String normalize(String fingerprint) {
        return fingerprint.replace(":", "").trim().toUpperCase(Locale.ROOT);
    }
}

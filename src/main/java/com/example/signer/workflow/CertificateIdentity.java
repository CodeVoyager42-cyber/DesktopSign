package com.example.signer.workflow;

import java.util.Locale;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Cryptographic identity of one workflow signer: the SHA-256 fingerprint of
 * the X.509 certificate whose private key must create this signer's
 * signature, plus descriptive fields for display and audit.
 *
 * <p>Instances are metadata, never cryptographic truth: matching is performed
 * by comparing the certificate embedded in a signature against the
 * {@code sha256Fingerprint} recorded here. Display names are never used for
 * identity decisions.
 *
 * <p>Instances are immutable and JSON-friendly (Jackson):
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps older builds
 * able to read manifests written by newer schema versions whose records
 * carry extra fields.
 *
 * @param sha256Fingerprint colon-separated uppercase SHA-256 of the DER
 *        certificate, e.g. {@code AB:CD:...}; {@code null} while the signer
 *        has not yet bound a certificate (UNBOUND enrollment state — the
 *        step exists, its cryptographic identity is pending)
 * @param subject certificate subject DN at assignment time; display/audit only
 * @param issuer certificate issuer DN; display/audit only
 * @param serialNumber certificate serial (hex); display/audit only
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CertificateIdentity(
        @JsonProperty("sha256Fingerprint") String sha256Fingerprint,
        @JsonProperty("subject") String subject,
        @JsonProperty("issuer") String issuer,
        @JsonProperty("serialNumber") String serialNumber) {

    public CertificateIdentity {
        if (sha256Fingerprint != null && sha256Fingerprint.isBlank()) {
            throw new IllegalArgumentException("sha256Fingerprint must not be blank");
        }
    }

    /** A not-yet-bound identity placeholder for workflows created without
     *  the signer's certificate (the signer binds it on their own machine). */
    public static CertificateIdentity unbound() {
        return new CertificateIdentity(null, "", "", "");
    }

    /** Whether this identity has a certificate bound to it. */
    public boolean isBound() {
        return sha256Fingerprint != null;
    }

    /** The single identity component matching is based on, or {@code null}. */
    public String fingerprint() {
        return sha256Fingerprint;
    }

    /**
     * True when {@code other} is the same certificate. Both sides are
     * normalized (colons removed, case-insensitive) so that fingerprints
     * recorded in different canonical formats still match (§16). An UNBOUND
     * identity matches nothing — a workflow step without a bound certificate
     * can never be fulfilled by accident.
     */
    public boolean matchesFingerprint(String otherFingerprint) {
        if (!isBound() || otherFingerprint == null) {
            return false;
        }
        return normalize(sha256Fingerprint).equals(normalize(otherFingerprint));
    }

    private static String normalize(String fingerprint) {
        return fingerprint.replace(":", "").trim()
                .toUpperCase(Locale.ROOT);
    }

    /** Short form for timelines and status lines ({@code AB:CD:…:EF (8)}). */
    public String fingerprintShort() {
        if (!isBound()) {
            return "(not yet bound)";
        }
        String hex = sha256Fingerprint.replace(":", "");
        return hex.length() <= 16 ? sha256Fingerprint
                : hex.substring(0, 8) + "…" + hex.substring(hex.length() - 8);
    }
}

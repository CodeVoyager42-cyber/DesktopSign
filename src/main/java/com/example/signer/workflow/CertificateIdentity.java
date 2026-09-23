package com.example.signer.workflow;

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
 *        certificate, e.g. {@code AB:CD:...}; never {@code null}
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
        Objects.requireNonNull(sha256Fingerprint, "sha256Fingerprint");
        if (sha256Fingerprint.isBlank()) {
            throw new IllegalArgumentException("sha256Fingerprint must not be blank");
        }
    }

    /** The single identity component matching is based on. */
    public String fingerprint() {
        return sha256Fingerprint;
    }

    /** True when {@code other} is the same certificate. */
    public boolean matchesFingerprint(String otherFingerprint) {
        return otherFingerprint != null
                && sha256Fingerprint.equalsIgnoreCase(
                        otherFingerprint.trim());
    }

    /** Short form for timelines and status lines ({@code AB:CD:…:EF (8)}). */
    public String fingerprintShort() {
        String hex = sha256Fingerprint.replace(":", "");
        return hex.length() <= 16 ? sha256Fingerprint
                : hex.substring(0, 8) + "…" + hex.substring(hex.length() - 8);
    }
}

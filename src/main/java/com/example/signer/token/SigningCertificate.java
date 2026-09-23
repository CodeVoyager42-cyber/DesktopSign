package com.example.signer.token;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HexFormat;

/**
 * A signing certificate available on the token together with the alias of the
 * private key it is paired with.
 *
 * <p>The private key itself never leaves the token; only the public
 * certificate material is exposed here.
 */
public final class SigningCertificate {

    private final String alias;
    private final X509Certificate certificate;
    private final String sha256Fingerprint;
    private final int keySize;

    public SigningCertificate(String alias, X509Certificate certificate) {
        this.alias = alias;
        this.certificate = certificate;
        this.sha256Fingerprint = computeFingerprint(certificate);
        this.keySize = computeKeySize(certificate.getPublicKey());
    }

    public String getAlias() {
        return alias;
    }

    public X509Certificate getCertificate() {
        return certificate;
    }

    public String getSubject() {
        return certificate.getSubjectX500Principal().getName();
    }

    public String getIssuer() {
        return certificate.getIssuerX500Principal().getName();
    }

    public String getSerialNumber() {
        return certificate.getSerialNumber().toString(16).toUpperCase();
    }

    public Instant getValidFrom() {
        return certificate.getNotBefore().toInstant();
    }

    public Instant getValidUntil() {
        return certificate.getNotAfter().toInstant();
    }

    public String getPublicKeyAlgorithm() {
        return certificate.getPublicKey().getAlgorithm();
    }

    public int getKeySize() {
        return keySize;
    }

    public String getSha256Fingerprint() {
        return sha256Fingerprint;
    }

    /**
     * SHA-256 fingerprint (colon-separated uppercase hex) of any X.509
     * certificate — the canonical portable identity used for workflow signer
     * matching. Shared by the token layer and the signature verifier so both
     * sides of a comparison compute the value identically.
     */
    public static String fingerprintOf(X509Certificate certificate) {
        return computeFingerprint(certificate);
    }

    private static String computeFingerprint(X509Certificate certificate) {
        try {
            byte[] der = certificate.getEncoded();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            String hex = HexFormat.of().withUpperCase().formatHex(digest);
            StringBuilder sb = new StringBuilder(hex.length() + hex.length() / 2 - 1);
            for (int i = 0; i < hex.length(); i += 2) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(hex, i, i + 2);
            }
            return sb.toString();
        } catch (Exception e) {
            return "<unavailable>";
        }
    }

    private static int computeKeySize(PublicKey publicKey) {
        if (publicKey instanceof RSAPublicKey rsa) {
            return rsa.getModulus().bitLength();
        }
        if (publicKey instanceof ECPublicKey ec) {
            return ec.getParams().getOrder().bitLength();
        }
        return -1;
    }

    @Override
    public String toString() {
        return "SigningCertificate{alias='" + alias + "', subject='" + getSubject() + "'}";
    }
}
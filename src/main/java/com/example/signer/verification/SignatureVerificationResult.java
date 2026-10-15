package com.example.signer.verification;

import java.util.Calendar;
import com.example.signer.token.SigningAlgorithm;

import java.time.Instant;
import java.util.List;

/**
 * Result of a signature verification, including per-signature detail for
 * every signature in the document's revision chain.
 *
 * <p>UIs should prefer {@link #signatures()} when presenting results: it
 * shows each signer, their algorithm and signing time, and whether their
 * signature is valid — even when the chain as a whole is broken.
 *
 * @param valid          whether the whole signature chain is currently valid
 *                       (every signature cryptographically correct, revisions
 *                       strictly appended, and nothing added after the most
 *                       recent signature)
 * @param algorithm      algorithm of the most recent signer
 * @param signerSubject  subject DN of the most recent signer
 * @param verifiedAt     when this verification ran
 * @param message        human-readable summary, fit for status bars
 * @param signatures     per-signature detail, ordered from oldest to newest
 */
public record SignatureVerificationResult(
        boolean valid,
        SigningAlgorithm algorithm,
        String signerSubject,
        Instant verifiedAt,
        String message,
        List<SignatureDetail> signatures) {

    /**
     * Detail for one signature in the chain.
     *
     * @param docMdp DocMDP access-permission value from a certification
     *               signature ({@code null} when the signature is not a
     *               certification signature or declares no permissions) —
     *               1 = no changes allowed, 2 = form fill/ signing allowed,
     *               3 = form fill allowed, 4 = allowed with revocation info
     */
    public record SignatureDetail(
            int index,
            String signerSubject,
            SigningAlgorithm algorithm,
            Instant signedAt,
            boolean valid,
            String message,
            String signerCertFingerprint,
            Integer docMdp,
            SignatureProperties properties) {

        /** Backward-compatible factory (no fingerprint, no DocMDP known). */
        public static SignatureDetail of(int index,
                                         String signerSubject,
                                         SigningAlgorithm algorithm,
                                         Calendar signDate,
                                         boolean valid,
                                         String message) {
            return of(index, signerSubject, algorithm, signDate, valid, message, null);
        }

        /** Backward-compatible factory (no DocMDP known). */
        public static SignatureDetail of(int index,
                                         String signerSubject,
                                         SigningAlgorithm algorithm,
                                         Calendar signDate,
                                         boolean valid,
                                         String message,
                                         String signerCertFingerprint) {
            return of(index, signerSubject, algorithm, signDate, valid, message,
                    signerCertFingerprint, null);
        }

        /** Full factory (all known detail). */
        public static SignatureDetail of(int index,
                                         String signerSubject,
                                         SigningAlgorithm algorithm,
                                         Calendar signDate,
                                         boolean valid,
                                         String message,
                                         String signerCertFingerprint,
                                         Integer docMdp) {
            return of(index, signerSubject, algorithm, signDate, valid, message,
                    signerCertFingerprint, docMdp, null);
        }

        /**
         * Full factory including the facts read from the embedded signature
         * and its certificate ({@code properties} may be {@code null} when
         * the CMS could not be parsed).
         */
        public static SignatureDetail of(int index,
                                         String signerSubject,
                                         SigningAlgorithm algorithm,
                                         Calendar signDate,
                                         boolean valid,
                                         String message,
                                         String signerCertFingerprint,
                                         Integer docMdp,
                                         SignatureProperties properties) {
            Instant signedAt = signDate != null
                    ? signDate.toInstant()
                    : null;
            return new SignatureDetail(index,
                    signerSubject != null ? signerSubject : "unknown",
                    algorithm, signedAt, valid, message, signerCertFingerprint,
                    docMdp, properties);
        }

        /** SHA-256 fingerprint of the signer certificate (colon-separated hex). */
        public String signerCertFingerprint() {
            return signerCertFingerprint;
        }

        public boolean signedAtKnown() {
            return signedAt != null;
        }

        /**
         * The DocMDP value this certification signature grants, or
         * {@code null} when this is an ordinary approval signature.
         */
        public Integer docMdp() {
            return docMdp;
        }
    }

    public static SignatureVerificationResult success(
            SigningAlgorithm algorithm, String signerSubject) {
        return success(algorithm, signerSubject, List.of());
    }

    public static SignatureVerificationResult success(
            SigningAlgorithm algorithm, String signerSubject, List<SignatureDetail> signatures) {
        return new SignatureVerificationResult(true, algorithm, signerSubject,
                Instant.now(), "Signature verified successfully", List.copyOf(signatures));
    }

    public static SignatureVerificationResult failure(
            String message, SigningAlgorithm algorithm, String signerSubject) {
        return failure(message, algorithm, signerSubject, List.of());
    }

    public static SignatureVerificationResult failure(
            String message, SigningAlgorithm algorithm, String signerSubject,
            List<SignatureDetail> signatures) {
        return new SignatureVerificationResult(false, algorithm, signerSubject,
                Instant.now(), message, List.copyOf(signatures));
    }
}

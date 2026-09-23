package com.example.signer.verification;

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

    public record SignatureDetail(
            int index,
            String signerSubject,
            SigningAlgorithm algorithm,
            Instant signedAt,
            boolean valid,
            String message) {

        /**
         * Factory converting the PDF {@code /M} signing date. A missing or
         * unparsable date yields {@code signedAt = null} — the signature can
         * still be valid, its signing time is simply unknown.
         */
        public static SignatureDetail of(int index,
                                         String signerSubject,
                                         SigningAlgorithm algorithm,
                                         java.util.Calendar signDate,
                                         boolean valid,
                                         String message) {
            Instant signedAt = signDate != null
                    ? signDate.toInstant()
                    : null;
            return new SignatureDetail(index,
                    signerSubject != null ? signerSubject : "unknown",
                    algorithm, signedAt, valid, message);
        }

        public boolean signedAtKnown() {
            return signedAt != null;
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

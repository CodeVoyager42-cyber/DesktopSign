package com.example.signer.verification;

import com.example.signer.token.SigningAlgorithm;

import java.time.Instant;

/**
 * Result of a signature verification.
 */
public record SignatureVerificationResult(
        boolean valid,
        SigningAlgorithm algorithm,
        String signerSubject,
        Instant verifiedAt,
        String message) {

    public static SignatureVerificationResult success(
            SigningAlgorithm algorithm, String signerSubject) {
        return new SignatureVerificationResult(true, algorithm, signerSubject,
                Instant.now(), "Signature verified successfully");
    }

    public static SignatureVerificationResult failure(
            String message, SigningAlgorithm algorithm, String signerSubject) {
        return new SignatureVerificationResult(false, algorithm, signerSubject,
                Instant.now(), message);
    }
}
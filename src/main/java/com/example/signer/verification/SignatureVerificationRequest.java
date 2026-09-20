package com.example.signer.verification;

import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;

import java.time.Instant;

/**
 * Request to verify a raw byte signature against a signing certificate and
 * algorithm.
 */
public record SignatureVerificationRequest(
        byte[] data,
        byte[] signature,
        SigningCertificate signerCertificate,
        SigningAlgorithm algorithm) {
}
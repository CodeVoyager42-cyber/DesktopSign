package com.example.signer.signing;

import com.example.signer.token.SigningAlgorithm;

import java.time.Instant;

/**
 * Outcome of a signing operation. Contains only the signature bytes plus safe
 * metadata - never key material.
 */
public record SigningResult(
        byte[] signature,
        SigningAlgorithm algorithm,
        String signerSubject,
        Instant signedAt) {

    public SigningResult(byte[] signature, SigningAlgorithm algorithm, String signerSubject) {
        this(signature, algorithm, signerSubject, Instant.now());
    }
}
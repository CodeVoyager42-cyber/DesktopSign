package com.example.signer.signing;

import com.example.signer.token.SigningAlgorithm;

/**
 * Request to sign data using a token-held private key.
 */
public record SigningRequest(
        byte[] data,
        String keyAlias,
        SigningAlgorithm algorithm) {
}
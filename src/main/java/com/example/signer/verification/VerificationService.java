package com.example.signer.verification;

/**
 * Service to verify raw byte signatures produced by a signing token against
 * the signer's public certificate.
 */
public interface VerificationService {

    SignatureVerificationResult verify(SignatureVerificationRequest request);
}
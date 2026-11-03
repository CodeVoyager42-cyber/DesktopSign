package com.example.signer.token;

/**
 * Signing algorithms supported by the token abstraction.
 */
public enum SigningAlgorithm {

    RSA_SHA256("SHA256withRSA"),
    ECDSA_SHA256("SHA256withECDSA");

    private final String jcaName;

    SigningAlgorithm(String jcaName) {
        this.jcaName = jcaName;
    }

    /**
     * JCA signature transformation name, e.g. {@code SHA256withRSA}.
     */
    public String getJcaName() {
        return jcaName;
    }
}
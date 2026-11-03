package com.example.signer.token;

/**
 * Read-only metadata about a detected cryptographic token.
 *
 * <p>Only safe, public metadata. Never contains key material or PIN data.
 */
public record TokenInfo(
        String label,
        String manufacturer,
        String model,
        String serialNumber) {

    @Override
    public String toString() {
        return "TokenInfo{label='" + label + "', manufacturer='" + manufacturer
                + "', model='" + model + "', serialNumber='" + serialNumber + "'}";
    }
}
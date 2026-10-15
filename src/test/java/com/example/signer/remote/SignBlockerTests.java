package com.example.signer.remote;

import com.example.signer.token.SigningAlgorithm;
import com.example.signer.verification.SignatureVerificationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The first signer's document has no signatures yet: that must not block signing. */
class SignBlockerTests {

    @Test
    void anUnsignedDocumentCanBeSigned() {
        SignatureVerificationResult unsigned =
                SignatureVerificationResult.failure("PDF is not signed", null, "unknown");
        assertThat(RemoteSigningJourney.existingSignaturesProblem(unsigned)).isNull();
        assertThat(RemoteSigningJourney.existingSignaturesProblem(null)).isNull();
    }

    @Test
    void aValidSignedDocumentCanBeSigned() {
        assertThat(RemoteSigningJourney.existingSignaturesProblem(
                SignatureVerificationResult.success(SigningAlgorithm.RSA_SHA256, "CN=Ann")))
                .isNull();
    }
}

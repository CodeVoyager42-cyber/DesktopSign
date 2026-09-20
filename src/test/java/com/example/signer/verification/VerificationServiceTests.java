package com.example.signer.verification;

import com.example.signer.signing.SigningRequest;
import com.example.signer.signing.SigningResult;
import com.example.signer.signing.SigningService;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VerificationServiceTests {

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private SigningService signingService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @AfterEach
    void cleanUp() {
        tokenService.disconnect();
        simulator.removeToken();
    }

    @Test
    void validSignatureIsAccepted() {
        byte[] data = "contract payload".getBytes(StandardCharsets.UTF_8);
        SigningResult signed = sign(data);

        SignatureVerificationResult result = verificationService.verify(
                new SignatureVerificationRequest(
                        data, signed.signature(),
                        tokenService.getCertificates().get(0), signed.algorithm()));

        assertThat(result.valid()).isTrue();
        assertThat(result.signerSubject()).contains("Mock Signer");
    }

    @Test
    void tamperedDataIsDetected() {
        byte[] data = "contract payload".getBytes(StandardCharsets.UTF_8);
        SigningResult signed = sign(data);

        byte[] tampered = Arrays.copyOf(data, data.length);
        tampered[0] ^= 0x01;

        SignatureVerificationResult result = verificationService.verify(
                new SignatureVerificationRequest(
                        tampered, signed.signature(),
                        tokenService.getCertificates().get(0), signed.algorithm()));

        assertThat(result.valid()).isFalse();
    }

    @Test
    void wrongCertificateIsRejected() {
        byte[] data = "contract payload".getBytes(StandardCharsets.UTF_8);
        SigningResult signed = sign(data);

        MockSigningToken otherToken = new MockSigningToken();
        otherToken.setPresent(true);
        SigningCertificate otherCertificate = otherToken.getSigningCertificates().get(0);

        SignatureVerificationResult result = verificationService.verify(
                new SignatureVerificationRequest(
                        data, signed.signature(), otherCertificate, signed.algorithm()));

        assertThat(result.valid()).isFalse();
    }

    private SigningResult sign(byte[] data) {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());
        return signingService.sign(new SigningRequest(
                data, MockSigningToken.DEFAULT_ALIAS, SigningAlgorithm.RSA_SHA256));
    }
}
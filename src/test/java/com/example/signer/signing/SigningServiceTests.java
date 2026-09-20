package com.example.signer.signing;

import com.example.signer.exception.SignerException;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.verification.SignatureVerificationRequest;
import com.example.signer.verification.SignatureVerificationResult;
import com.example.signer.verification.VerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SigningServiceTests {

    @Autowired
    private SigningService signingService;

    @Autowired
    private VerificationService verificationService;

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
    void signAndVerifySuccessfully() {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        byte[] data = "document hash".getBytes(StandardCharsets.UTF_8);
        SigningResult result = signingService.sign(new SigningRequest(
                data,
                MockSigningToken.DEFAULT_ALIAS,
                com.example.signer.token.SigningAlgorithm.RSA_SHA256));

        assertThat(result.signature()).isNotEmpty();
        assertThat(result.signerSubject()).contains("Mock Signer");
        assertThat(result.algorithm()).isEqualTo(com.example.signer.token.SigningAlgorithm.RSA_SHA256);

        SignatureVerificationResult verification = verificationService.verify(
                new SignatureVerificationRequest(data, result.signature(),
                        tokenService.getCertificates().get(0),
                        result.algorithm()));
        assertThat(verification.valid()).isTrue();
    }

    @Test
    void signWithoutAuthenticationIsRejected() {
        simulator.insertToken();
        tokenService.detect();

        assertThatThrownBy(() -> signingService.sign(new SigningRequest(
                new byte[]{1}, MockSigningToken.DEFAULT_ALIAS,
                com.example.signer.token.SigningAlgorithm.RSA_SHA256)))
                .isInstanceOfSatisfying(SignerException.class, e ->
                        assertThat(e.getMessage()).containsIgnoringCase("authenticated"));
    }
}
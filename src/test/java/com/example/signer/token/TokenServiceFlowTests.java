package com.example.signer.token;

import com.example.signer.exception.ErrorCode;
import com.example.signer.signing.SigningRequest;
import com.example.signer.signing.SigningResult;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end verification of the application token state flow using the
 * in-memory mock token:
 *
 * <pre>
 * NO_TOKEN -&gt; TOKEN_DETECTED -&gt; CERTIFICATE_AVAILABLE -&gt; WAITING_FOR_PIN -&gt; AUTHENTICATED
 * </pre>
 */
@SpringBootTest
class TokenServiceFlowTests {

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @AfterEach
    void cleanUp() {
        simulator.removeToken();
        tokenService.disconnect();
    }

    @Test
    void initialStateIsNoToken() {
        assertThat(tokenService.getState()).isEqualTo(TokenState.NO_TOKEN);
    }

    @Test
    void detectionWithoutTokenLeavesNoTokenState() {
        tokenService.detect();
        assertThat(tokenService.getState()).isEqualTo(TokenState.NO_TOKEN);
    }

    @Test
    void fullFlowFromNoTokenToAuthenticatedToSignature() {
        simulator.insertToken();
        tokenService.detect();
        assertThat(tokenService.getState()).isEqualTo(TokenState.CERTIFICATE_AVAILABLE);

        assertThat(tokenService.getCertificates()).hasSize(3);
        SigningCertificate certificate = tokenService.getCertificates().stream()
                .filter(c -> MockSigningToken.DEFAULT_ALIAS.equals(c.getAlias()))
                .findFirst()
                .orElseThrow();
        assertThat(certificate.getAlias()).isEqualTo("mock-signing-key");
        assertThat(certificate.getSubject()).contains("Mock Signer");
        assertThat(certificate.getKeySize()).isEqualTo(2048);
        assertThat(certificate.getPublicKeyAlgorithm()).isEqualTo("RSA");
        assertThat(certificate.getSha256Fingerprint()).isNotBlank();
        assertThat(tokenService.getTokenInfo().label()).contains("Mock");

        tokenService.startAuthentication();
        assertThat(tokenService.getState()).isEqualTo(TokenState.WAITING_FOR_PIN);

        tokenService.authenticate(pin("1234"));
        assertThat(tokenService.getState()).isEqualTo(TokenState.AUTHENTICATED);
        assertThat(tokenService.isAuthenticated()).isTrue();

        SigningResult result = tokenService.sign(new SigningRequest(
                "hello".getBytes(StandardCharsets.UTF_8),
                certificate.getAlias(),
                SigningAlgorithm.RSA_SHA256));
        assertThat(tokenService.getState()).isEqualTo(TokenState.SIGNATURE_COMPLETE);
        assertThat(result.signature()).isNotEmpty();
        assertThat(result.signerSubject()).contains("Mock Signer");
    }

    @Test
    void wrongPinLeavesStateWaitingForPin() {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();

        assertThatThrownBy(() -> tokenService.authenticate(pin("0000")))
                .isInstanceOfSatisfying(TokenException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOKEN_PIN_INCORRECT));
        assertThat(tokenService.getState()).isEqualTo(TokenState.WAITING_FOR_PIN);
    }

    @Test
    void threeWrongPinsLockTheToken() {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();

        assertThatThrownBy(() -> tokenService.authenticate(pin("0000")))
                .isInstanceOfSatisfying(TokenException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOKEN_PIN_INCORRECT));

        assertThatThrownBy(() -> tokenService.authenticate(pin("0000")))
                .isInstanceOfSatisfying(TokenException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOKEN_PIN_INCORRECT));

        assertThatThrownBy(() -> tokenService.authenticate(pin("0000")))
                .isInstanceOfSatisfying(TokenException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOKEN_PIN_LOCKED));

        assertThat(tokenService.getState()).isEqualTo(TokenState.CERTIFICATE_AVAILABLE);
    }

    @Test
    void signWithoutAuthenticationIsRejected() {
        simulator.insertToken();
        tokenService.detect();

        assertThatThrownBy(() -> tokenService.sign(new SigningRequest(
                new byte[]{1, 2, 3}, "mock-signing-key", SigningAlgorithm.RSA_SHA256)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void logoutReturnsToCertificateAvailable() {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(pin("1234"));
        assertThat(tokenService.getState()).isEqualTo(TokenState.AUTHENTICATED);

        tokenService.logout();
        assertThat(tokenService.getState()).isEqualTo(TokenState.CERTIFICATE_AVAILABLE);
    }

    @Test
    void cancelAuthenticationReturnsToCertificateAvailable() {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        assertThat(tokenService.getState()).isEqualTo(TokenState.WAITING_FOR_PIN);

        tokenService.cancelAuthentication();
        assertThat(tokenService.getState()).isEqualTo(TokenState.CERTIFICATE_AVAILABLE);
    }

    @Test
    void tokenRemovalAtAnyPointReturnsToNoToken() {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(pin("1234"));
        assertThat(tokenService.getState()).isEqualTo(TokenState.AUTHENTICATED);

        simulator.removeToken();
        tokenService.disconnect();
        assertThat(tokenService.getState()).isEqualTo(TokenState.NO_TOKEN);
    }

    @Test
    void removalDetectedByScanReturnsToNoToken() {
        simulator.insertToken();
        tokenService.detect();
        assertThat(tokenService.getState()).isEqualTo(TokenState.CERTIFICATE_AVAILABLE);

        simulator.removeToken();
        tokenService.detect();
        assertThat(tokenService.getState()).isEqualTo(TokenState.NO_TOKEN);
    }

    @Test
    void tokenCanBeReconnectedAfterRemoval() {
        simulator.insertToken();
        tokenService.detect();
        assertThat(tokenService.getState()).isEqualTo(TokenState.CERTIFICATE_AVAILABLE);

        simulator.removeToken();
        tokenService.detect();
        assertThat(tokenService.getState()).isEqualTo(TokenState.NO_TOKEN);

        simulator.insertToken();
        tokenService.detect();
        assertThat(tokenService.getState()).isEqualTo(TokenState.CERTIFICATE_AVAILABLE);
        assertThat(tokenService.getCertificates()).hasSize(3);
    }

    @Test
    void lockedTokenUnlocksAfterReinsertion() {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        try {
            tokenService.authenticate(pin("0000"));
        } catch (TokenException ignored) {
        }
        try {
            tokenService.authenticate(pin("0000"));
        } catch (TokenException ignored) {
        }
        assertThatThrownBy(() -> tokenService.authenticate(pin("0000")))
                .isInstanceOfSatisfying(TokenException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOKEN_PIN_LOCKED));

        tokenService.disconnect();
        simulator.removeToken();

        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(pin("1234"));
        assertThat(tokenService.getState()).isEqualTo(TokenState.AUTHENTICATED);
    }

    @Test
    void startAuthenticationIsNoOpWhenSessionAlreadyUnlocked() {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(pin("1234"));
        assertThat(tokenService.getState()).isEqualTo(TokenState.AUTHENTICATED);

        // Retry paths (e.g. a re-run remote signing attempt) must not be
        // blocked by the state machine: the session is already unlocked.
        tokenService.startAuthentication();
        assertThat(tokenService.getState()).isEqualTo(TokenState.AUTHENTICATED);
    }

    @Test
    void authenticateIsNoOpWhenSessionAlreadyUnlocked() {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(pin("1234"));

        // A replayed authenticate() is ignored, PIN content is irrelevant.
        tokenService.authenticate(pin("9999"));
        assertThat(tokenService.getState()).isEqualTo(TokenState.AUTHENTICATED);
        assertThat(tokenService.isAuthenticated()).isTrue();
    }

    @Test
    void fullAuthFlowCanBeReplayedAfterASignature() {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(pin("1234"));
        tokenService.sign(new SigningRequest(
                "hello".getBytes(StandardCharsets.UTF_8),
                MockSigningToken.DEFAULT_ALIAS,
                SigningAlgorithm.RSA_SHA256));
        assertThat(tokenService.getState()).isEqualTo(TokenState.SIGNATURE_COMPLETE);

        // A retried remote flow replays detect -> startAuthentication ->
        // authenticate; all three are no-ops on an unlocked session.
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(pin("1234"));
        assertThat(tokenService.getState()).isEqualTo(TokenState.SIGNATURE_COMPLETE);
        assertThat(tokenService.isAuthenticated()).isFalse();
    }

    private static char[] pin(String value) {
        return value.toCharArray();
    }
}
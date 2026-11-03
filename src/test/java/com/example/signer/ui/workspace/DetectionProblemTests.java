package com.example.signer.ui.workspace;

import com.example.signer.exception.ErrorCode;
import com.example.signer.token.TokenException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A failed smart-card lookup must tell the user why, never stay silent. */
class DetectionProblemTests {

    @Test
    void driverProblemsAreNamedAsSuch() {
        String text = SigningWorkspaceFlow.detectionProblem(new TokenException(
                ErrorCode.PKCS11_INITIALIZATION_FAILED, "missing --add-exports"));
        assertThat(text).startsWith("Smart card driver problem").contains("missing --add-exports");
    }

    @Test
    void otherFailuresStillShowAMessage() {
        assertThat(SigningWorkspaceFlow.detectionProblem(new IllegalStateException("boom")))
                .isEqualTo("The smart card could not be read: boom");
        assertThat(SigningWorkspaceFlow.detectionProblem(null))
                .isEqualTo("The smart card could not be read.");
    }
}

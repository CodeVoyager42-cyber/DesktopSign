package com.example.signer.ui.dialog;

import com.example.signer.client.RemoteApiException;
import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase B: what the desktop says when a launch link cannot be used. */
class LaunchDialogTextTests {

    private static RemoteApiException server(String code, int status, String message) {
        return new RemoteApiException(ErrorCode.TOKEN_NOT_CONNECTED, message, status, code);
    }

    @Test
    void everyRefusedCodeGetsTheSameAdvice() {
        String[] text = LaunchDialog.problemText(server("LAUNCH_CODE_INVALID", 400, "x"));
        assertThat(text[0]).isEqualTo("This signing link can't be used");
        assertThat(text[1]).contains("2 minutes").contains("website");
    }

    @Test
    void anOldDesktopIsToldToUpdateInTheServersWords() {
        String[] text = LaunchDialog.problemText(server("UPGRADE_REQUIRED", 426,
                "Install version 1.2.0 or newer"));
        assertThat(text[0]).isEqualTo("Please update SignFlow");
        assertThat(text[1]).contains("1.2.0");
    }

    @Test
    void networkAndUnknownFailuresAreExplainedWithoutInternals() {
        SignerException offline = new SignerException(ErrorCode.TOKEN_NOT_CONNECTED, "connect failed",
                new IOException("Connection refused"));
        assertThat(LaunchDialog.problemText(offline)[0]).contains("can't be reached");
        String[] other = LaunchDialog.problemText(new IllegalStateException("secret-ish detail"));
        assertThat(String.join(" ", other)).doesNotContain("secret-ish");
    }
}

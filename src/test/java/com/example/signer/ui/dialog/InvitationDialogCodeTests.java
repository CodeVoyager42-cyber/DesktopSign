package com.example.signer.ui.dialog;

import com.example.signer.client.RemoteApiException;
import com.example.signer.exception.ErrorCode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Security Phase 3: which failures keep the user on the code screen. */
class InvitationDialogCodeTests {

    private static RemoteApiException api(int status, String code, String message) {
        return new RemoteApiException(ErrorCode.WORKFLOW_INVALID, message, status, code);
    }

    @Test
    void codeProblemsStayOnTheCodeScreenWithTheServerMessage() {
        assertThat(InvitationDialog.codeProblem(api(403, "GUEST_CODE_INVALID",
                "The code is not correct"))).isEqualTo("The code is not correct");
        assertThat(InvitationDialog.codeProblem(api(410, "GUEST_CODE_EXPIRED",
                "Request a new one"))).isEqualTo("Request a new one");
        assertThat(InvitationDialog.codeProblem(api(403, "GUEST_VERIFICATION_REQUIRED", "")))
                .isNotBlank();
        assertThat(InvitationDialog.codeProblem(api(502, "EMAIL_DELIVERY_FAILED",
                "Could not be e-mailed"))).isEqualTo("Could not be e-mailed");
    }

    @Test
    void invitationProblemsAreNotCodeProblems() {
        assertThat(InvitationDialog.codeProblem(api(409, "INVITATION_REVOKED", "x"))).isNull();
        assertThat(InvitationDialog.codeProblem(api(410, "INVITATION_EXPIRED", "x"))).isNull();
        assertThat(InvitationDialog.codeProblem(new IllegalStateException("boom"))).isNull();
    }
}

package com.example.signer.client;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.server.api.ApiContracts.InvitationPreviewView;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Each invitation situation maps to its own screen state. */
class InvitationStateTests {

    private static RemoteApiException server(int status, String code) {
        return new RemoteApiException(ErrorCode.WORKFLOW_INVALID, "x", status, code);
    }

    @Test
    void serverErrorCodesMapToDistinctStates() {
        assertThat(InvitationState.of(server(410, "INVITATION_EXPIRED")))
                .isEqualTo(InvitationState.EXPIRED);
        assertThat(InvitationState.of(server(409, "INVITATION_REVOKED")))
                .isEqualTo(InvitationState.REVOKED);
        assertThat(InvitationState.of(server(409, "INVITATION_ALREADY_USED")))
                .isEqualTo(InvitationState.ALREADY_USED);
        assertThat(InvitationState.of(server(409, "INVITATION_COMPLETED")))
                .isEqualTo(InvitationState.COMPLETED);
        assertThat(InvitationState.of(server(404, "INVITATION_NOT_FOUND")))
                .isEqualTo(InvitationState.INVALID);
        assertThat(InvitationState.of(server(404, null))).isEqualTo(InvitationState.INVALID);
        assertThat(InvitationState.of(server(500, null))).isEqualTo(InvitationState.SERVER_ERROR);
    }

    @Test
    void noResponseIsANetworkError() {
        SignerException refused = new SignerException(ErrorCode.WORKFLOW_INVALID,
                "Could not connect", new java.net.ConnectException("refused"));
        assertThat(InvitationState.of(refused)).isEqualTo(InvitationState.NETWORK_ERROR);
        assertThat(InvitationState.NETWORK_ERROR.retryable()).isTrue();
        assertThat(InvitationState.EXPIRED.retryable()).isFalse();
    }

    @Test
    void previewStatusMapsToState() {
        assertThat(InvitationState.of(preview("PENDING"))).isEqualTo(InvitationState.VALID);
        assertThat(InvitationState.of(preview("REVOKED"))).isEqualTo(InvitationState.REVOKED);
        assertThat(InvitationState.of(preview("DECLINED"))).isEqualTo(InvitationState.REVOKED);
        // Opened before, not yet signed: may be reopened (COMPLETED comes as an error code).
        assertThat(InvitationState.of(preview("ACCEPTED"))).isEqualTo(InvitationState.VALID);
        assertThat(InvitationState.of((InvitationPreviewView) null)).isEqualTo(InvitationState.INVALID);
    }

    @Test
    void everyStateHasItsOwnMessage() {
        java.util.Set<String> titles = new java.util.HashSet<>();
        for (InvitationState state : InvitationState.values()) {
            assertThat(state.title()).isNotBlank();
            assertThat(state.explanation()).isNotBlank();
            assertThat(titles.add(state.title())).as(state.name()).isTrue();
        }
    }

    private static InvitationPreviewView preview(String status) {
        return new InvitationPreviewView("WF-1", "CV.pdf", "alice", "bob@example.test",
                status, "2026-10-09T10:00:00Z", false);
    }
}

package com.example.signer.ui.dialog;

import com.example.signer.client.WorkflowApiClient;
import com.example.signer.exception.SignerException;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowResponse;
import com.example.signer.server.api.ApiContracts.InvitationStatusView;
import com.example.signer.server.api.ApiContracts.InvitationView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@link com.example.signer.ui.dialog.InviterDialog} data path, over the live
 * coordination server and through the same client the desktop uses.
 *
 * <p>Reproduces the two ways "I click Send invite and nothing happens" used
 * to end in silence, and pins the fix:
 *
 * <ol>
 *   <li>the workflow picker is filled from {@code GET /owned} — if that list
 *   is empty the dialog has nothing to select and its button never arms, so
 *   a freshly created workflow must appear there;</li>
 *   <li>an invitation can only go to the slot's ASSIGNED address, so the
 *   dialog reads that address from {@code GET /{id}/invitations} and offers
 *   it — sending to it succeeds and hands back the emailed link, while any
 *   other address is refused with the assigned address named in the
 *   message the dialog shows.</li>
 * </ol>
 *
 * <p>Runs against the live server (skipped, never failed, when it is not
 * reachable — the same convention as the other live tests).
 */
class InviterDialogContractTests {

    private static final String BASE = "http://localhost:8080";

    private WorkflowApiClient alice;

    @BeforeEach
    void requireLiveServer() {
        Assumptions.assumeTrue(serverReachable(),
                "Live coordination server on " + BASE + " is not running");
    }

    @AfterEach
    void closeClient() {
        if (alice != null) {
            alice.close();
        }
    }

    @Test
    void thePickerListsOwnedWorkflowsAndTheInviteGoesToTheSlotAddress()
            throws Exception {
        alice = WorkflowApiClient.login(BASE, "alice", "alice-pass".toCharArray());
        String email = "inviter-contract-" + System.nanoTime() + "@example.test";
        CreateWorkflowResponse created = alice.createWorkflow(
                "inviter-contract.pdf", "SEQUENTIAL",
                List.of(new CreateWorkflowRequest.SignerSpec(1, email, null)),
                minimalPdf());
        String workflowId = created.workflow().workflowId();

        // 1. The picker's source. An empty list here IS the reported bug:
        //    nothing can be selected, so Send has nothing to send.
        assertThat(alice.myWorkflows())
                .as("the workspace inviter can only list workflows the caller"
                        + " created")
                .anyMatch(w -> w.workflowId().equals(workflowId));

        // 2. The address the selected slot belongs to — what the dialog now
        //    shows and offers in the email field.
        List<InvitationStatusView> slots = alice.workflowInvitations(workflowId);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).signerOrder()).isEqualTo(1);
        assertThat(slots.get(0).email())
                .as("the slot's own address is what an invitation may target")
                .isEqualTo(email);
        assertThat(slots.get(0).slotSigned()).isFalse();

        // 3. Any other address is refused BY NAME, so the dialog shows a
        //    reason instead of appearing to do nothing.
        String stranger = "stranger-" + System.nanoTime() + "@example.test";
        assertThatThrownBy(() -> alice.invite(workflowId, 1, stranger))
                .isInstanceOf(SignerException.class)
                .hasMessageContaining(email);

        // 4. Sending to the slot's own address succeeds and returns the exact
        //    emailed link (the dialog's "Invitation sent" confirmation).
        InvitationView sent = alice.invite(workflowId, 1, email);
        assertThat(sent.workflowId()).isEqualTo(workflowId);
        assertThat(sent.signerOrder()).isEqualTo(1);
        assertThat(sent.email()).isEqualTo(email);
        assertThat(sent.invitationUrl()).isNotBlank();
    }

    /** A one-page PDF the server can parse. */
    private byte[] minimalPdf() throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static boolean serverReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 8080), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

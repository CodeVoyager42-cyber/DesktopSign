package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.repository.InvitationRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 3: outside development the sender never receives the
 * invitee's raw link — creating, inviting and re-sending report who was
 * e-mailed, but no URL. (The {@code dev} profile turns exposure on; this test
 * switches it back off, as on a production server.)
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-links",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}",
                "signature.remote-server.invitation.expose-links-to-sender=false"
        })
@ActiveProfiles("server")
@Import(InvitationSignerLifecycleTests.CapturedMailbox.class)
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class InvitationLinkExposureTests {

    @Autowired
    private WorkflowInvitationCoordinator coordinator;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Test
    void theSenderLearnsWhoWasEmailedButNeverTheLink() {
        String email = "hidden" + System.nanoTime() + "@example.test";
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("hidden.pdf", "SEQUENTIAL", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, email, null))), "alice");
        WorkflowView view = workflows.createWorkflow(prep.request(), pdf(), "alice");
        InvitationSignerLifecycleTests.CapturedMailbox.LINKS.clear();
        List<InvitationLinkView> sent = coordinator.invite(view.workflowId(),
                prep.pendingInvites(), "alice");

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).email()).isEqualTo(email);
        assertThat(sent.get(0).invitationUrl()).isNull();
        // The invitee still received a working link by e-mail.
        assertThat(InvitationSignerLifecycleTests.CapturedMailbox.LINKS.get(email))
                .contains("/invitations/");

        Long wfId = workflowRepository.findByWorkflowId(view.workflowId()).orElseThrow().getId();
        Long invitationId = invitationRepository.findByWorkflowId(wfId).stream()
                .map(InvitationEntity::getId).max(Long::compare).orElseThrow();
        assertThat(invitations.resend(invitationId, "alice").invitationUrl()).isNull();
        assertThat(invitations.senderVisibleUrl("any-token")).isNull();
    }

    private static byte[] pdf() {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

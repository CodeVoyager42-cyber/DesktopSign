package com.example.signer.server;

import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.db.entity.AuditEventEntity;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.repository.AuditEventRepository;
import com.example.signer.server.db.repository.InvitationRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.invites.EmailService;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A failed send must not undo the invitation it belongs to (brief §37).
 *
 * <p>This is the difference between a recoverable and a dead-end failure.
 * When the mailbox rejects the message, the owner has to be able to press
 * Resend — but the resend endpoint works on an invitation ROW, so if the
 * failed send rolls the transaction back there is nothing left to resend
 * and the only record of the token is an audit event. The row therefore
 * commits with {@code email_status = FAILED}, and the caller still learns
 * that delivery failed (502 {@code EMAIL_DELIVERY_FAILED}).
 *
 * <p>Runs against live PostgreSQL (like {@code PostgresWorkflowStoreLiveTests},
 * whose provisioning probe it reuses) with an {@link EmailService} that fails
 * the way an unreachable SMTP host does.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-mail-failure",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}"
        })
@Import(InvitationEmailFailureTests.UnreachableMailbox.class)
@EnabledIf(value = "com.example.signer.server.PostgresWorkflowStoreLiveTests#postgresReachable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class InvitationEmailFailureTests {

    /**
     * A mailbox that always refuses the message — the same observable
     * behaviour as an unreachable SMTP host or a rejected credential.
     */
    @TestConfiguration
    static class UnreachableMailbox {

        @Bean
        @Primary
        EmailService unreachableMailbox() {
            return (recipientEmail, recipientName, workflowName, inviterName,
                    invitationUrl, expiresAt) -> {
                throw new IllegalStateException("mailbox unreachable (test)");
            };
        }
    }

    @Autowired
    private InvitationService invitations;

    @Autowired
    private WorkflowInvitationCoordinator coordinator;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private AuditEventRepository auditRepository;

    @Test
    void aFailedSendStillCommitsTheInvitationSoTheOwnerCanResend() {
        String email = "mailfail" + System.nanoTime() + "@Example.com";

        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("mail-failure.pdf",
                        List.of(new CreateWorkflowRequest.SignerSpec(1, email, null))),
                "alice");
        var view = workflows.createWorkflow(prep.request(),
                "%PDF-1.4 mail failure test".getBytes(), "alice");
        Long workflowDbId = workflowRepository.findByWorkflowId(view.workflowId())
                .orElseThrow().getId();

        // ── the send fails, and the caller is told so ─────────────────────
        assertThatThrownBy(() -> coordinator.invite(view.workflowId(),
                prep.pendingInvites(), "alice"))
                .isInstanceOf(InvitationService.EmailDeliveryException.class)
                .hasMessageContaining("Resend to try again")
                // The HTTP layer still answers 502 EMAIL_DELIVERY_FAILED.
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.EMAIL_DELIVERY_FAILED);

        // ── ...but the invitation row survives, marked FAILED ─────────────
        List<InvitationEntity> rows =
                invitationRepository.findByWorkflowId(workflowDbId);
        assertThat(rows).hasSize(1);
        InvitationEntity failed = rows.get(0);
        assertThat(failed.getStatus()).isEqualTo("PENDING");
        assertThat(failed.getEmailStatus()).isEqualTo("FAILED");
        assertThat(failed.getEmail()).isEqualTo(email.toLowerCase());
        // The token hash is there, so a retry can still supersede it.
        assertThat(failed.getTokenHash()).matches("[0-9a-f]{64}");
        assertThat(failed.getExpiresAt()).isNotNull();

        // The failure is on the audit trail too, not only in the log.
        assertThat(auditRepository.findByWorkflowIdOrderByEventSeq(workflowDbId))
                .extracting(AuditEventEntity::getEventType)
                .contains(AuditEventType.EMAIL_DELIVERY_FAILED.name());

        // ── the owner sees a row that can be retried ──────────────────────
        var tracked = invitations.listForWorkflow(view.workflowId(), "alice",
                Map.of(1, false));
        assertThat(tracked).singleElement().satisfies(shown -> {
            assertThat(shown.emailStatus()).isEqualTo("FAILED");
            assertThat(shown.status()).isEqualTo("PENDING");
            assertThat(shown.invitationId()).isEqualTo(failed.getId());
        });

        // ── Resend still works on that row: it supersedes and tries again ─
        assertThatThrownBy(() -> invitations.resend(failed.getId(), "alice"))
                .isInstanceOf(InvitationService.EmailDeliveryException.class);

        List<InvitationEntity> after =
                invitationRepository.findByWorkflowId(workflowDbId);
        assertThat(after).hasSize(2);
        assertThat(after).filteredOn(i -> i.getStatus().equals("REVOKED"))
                .singleElement()
                .satisfies(old -> assertThat(old.getId()).isEqualTo(failed.getId()));
        assertThat(after).filteredOn(i -> i.getStatus().equals("PENDING"))
                .singleElement()
                .satisfies(replacement -> {
                    assertThat(replacement.getEmailStatus()).isEqualTo("FAILED");
                    assertThat(replacement.getTokenHash())
                            .isNotEqualTo(failed.getTokenHash());
                });
    }
}

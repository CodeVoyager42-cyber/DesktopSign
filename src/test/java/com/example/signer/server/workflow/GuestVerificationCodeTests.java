package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.repository.InvitationRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;
import com.example.signer.server.security.RateLimitTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security Phase 3: the one-time code that opens a guest signing session.
 * Exercised at the service (transaction) boundary, database mode.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-guestcode",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}"
        })
@ActiveProfiles("server")
@Import(InvitationSignerLifecycleTests.CapturedMailbox.class)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIf(value = "com.example.signer.server.workflow.PostgresConcurrencyLiveTests#postgresAvailable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class GuestVerificationCodeTests {

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

    private record Invite(String workflowId, String email, String token) {
    }

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void emptyMailbox() {
        InvitationSignerLifecycleTests.CapturedMailbox.CODES.clear();
        // D3: guest-code requests are limited per client address, and that
        // count is shared and persistent now — an earlier class may have
        // spent this address's budget.
        RateLimitTestSupport.clear(dataSource);
    }

    private Invite invite() {
        String email = "code" + System.nanoTime() + "@example.test";
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("code.pdf", "SEQUENTIAL", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, email, null))), "alice");
        WorkflowView view = workflows.createWorkflow(prep.request(), pdf(), "alice");
        List<InvitationLinkView> links = coordinator.invite(view.workflowId(),
                prep.pendingInvites(), "alice");
        String url = links.get(0).invitationUrl();
        return new Invite(view.workflowId(), email, url.substring(url.lastIndexOf('/') + 1));
    }

    private String sendCode(Invite invite) {
        invitations.requestGuestCode(invite.token());
        return InvitationSignerLifecycleTests.CapturedMailbox.CODES.get(invite.email());
    }

    private InvitationEntity row(Invite invite) {
        Long id = workflowRepository.findByWorkflowId(invite.workflowId()).orElseThrow().getId();
        return invitationRepository.findByWorkflowId(id).stream()
                .max(java.util.Comparator.comparing(InvitationEntity::getId)).orElseThrow();
    }

    private static String other(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private static ApiErrorCode codeOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (ApiException e) {
            return e.code();
        }
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

    /* ------------------------------------------------------------------ */

    @Test
    void theLinkAloneNoLongerOpensASession() {
        Invite invite = invite();
        assertThat(codeOf(() -> invitations.openGuestSession(invite.token(), null)))
                .isEqualTo(ApiErrorCode.GUEST_VERIFICATION_REQUIRED);
        assertThat(codeOf(() -> invitations.openGuestSession(invite.token(), "123456")))
                .isEqualTo(ApiErrorCode.GUEST_CODE_EXPIRED);   // no code was ever sent
        assertThat(row(invite).getStatus()).isEqualTo(InvitationEntity.STATUS_PENDING);
    }

    @Test
    void aCodeOpensOneSessionOnlyAndOnlyItsOwnInvitation() {
        Invite first = invite();
        Invite second = invite();
        String code = sendCode(first);
        assertThat(code).matches("\\d{6}");
        String secondCode = sendCode(second);

        // A code never works for another invitation (codes are bound to it).
        if (!code.equals(secondCode)) {
            assertThat(codeOf(() -> invitations.openGuestSession(second.token(), code)))
                    .isEqualTo(ApiErrorCode.GUEST_CODE_INVALID);
        }

        assertThat(invitations.openGuestSession(first.token(), code).workflowId())
                .isEqualTo(first.workflowId());
        // Single use.
        assertThat(codeOf(() -> invitations.openGuestSession(first.token(), code)))
                .isEqualTo(ApiErrorCode.GUEST_CODE_EXPIRED);
    }

    @Test
    void fiveWrongEntriesKillTheCodeAndAreCountedDespiteTheError() {
        Invite invite = invite();
        String code = sendCode(invite);
        for (int i = 1; i <= 4; i++) {
            assertThat(codeOf(() -> invitations.openGuestSession(invite.token(), other(code))))
                    .isEqualTo(ApiErrorCode.GUEST_CODE_INVALID);
            assertThat(row(invite).getGuestCodeAttempts()).isEqualTo(i);
        }
        assertThat(codeOf(() -> invitations.openGuestSession(invite.token(), other(code))))
                .isEqualTo(ApiErrorCode.GUEST_CODE_EXPIRED);
        // Dead now, even with the right code.
        assertThat(codeOf(() -> invitations.openGuestSession(invite.token(), code)))
                .isEqualTo(ApiErrorCode.GUEST_CODE_EXPIRED);
        assertThat(row(invite).getGuestCodeFailures()).isEqualTo(5);
    }

    @Test
    void anExpiredCodeIsRefused() {
        Invite invite = invite();
        String code = sendCode(invite);
        InvitationEntity row = row(invite);
        row.startGuestCode(row.getGuestCodeHash(), Instant.now().minusSeconds(700),
                Instant.now().minusSeconds(1));
        invitationRepository.save(row);
        assertThat(codeOf(() -> invitations.openGuestSession(invite.token(), code)))
                .isEqualTo(ApiErrorCode.GUEST_CODE_EXPIRED);
    }

    @Test
    void aNewCodeIsNotSentWithinAMinuteButCanBeAfterwards() {
        Invite invite = invite();
        String code = sendCode(invite);
        InvitationSignerLifecycleTests.CapturedMailbox.CODES.clear();
        InvitationService.GuestCodeSent again = invitations.requestGuestCode(invite.token());
        assertThat(InvitationSignerLifecycleTests.CapturedMailbox.CODES).isEmpty();
        assertThat(again.resendAfterSeconds()).isBetween(1L, 60L);
        assertThat(again.maskedEmail()).startsWith(invite.email().substring(0, 1) + "***@");

        InvitationEntity row = row(invite);
        row.startGuestCode(row.getGuestCodeHash(), Instant.now().minusSeconds(61),
                row.getGuestCodeExpiresAt());
        invitationRepository.save(row);
        String fresh = sendCode(invite);
        assertThat(fresh).isNotNull();
        // Only the newest code works.
        if (!fresh.equals(code)) {
            assertThat(codeOf(() -> invitations.openGuestSession(invite.token(), code)))
                    .isEqualTo(ApiErrorCode.GUEST_CODE_INVALID);
        }
        assertThat(invitations.openGuestSession(invite.token(), fresh)).isNotNull();
    }

    @Test
    void fifteenWrongEntriesLockTheInvitationUntilTheOwnerResendsIt() {
        Invite invite = invite();
        for (int round = 0; round < 3; round++) {
            InvitationEntity row = row(invite);
            if (row.getGuestCodeSentAt() != null) {   // skip the one-minute throttle
                row.startGuestCode(row.getGuestCodeHash(), Instant.now().minusSeconds(61),
                        row.getGuestCodeExpiresAt());
                invitationRepository.save(row);
            }
            String code = sendCode(invite);
            for (int i = 0; i < 5; i++) {
                assertThat(codeOf(() -> invitations.openGuestSession(invite.token(), other(code))))
                        .isIn(ApiErrorCode.GUEST_CODE_INVALID, ApiErrorCode.GUEST_CODE_EXPIRED);
            }
        }
        assertThat(row(invite).getGuestCodeFailures()).isEqualTo(15);
        // Locked: no new code, no session — even after the throttle.
        InvitationEntity row = row(invite);
        row.startGuestCode(null, Instant.now().minusSeconds(61), null);
        invitationRepository.save(row);
        assertThat(codeOf(() -> invitations.requestGuestCode(invite.token())))
                .isEqualTo(ApiErrorCode.GUEST_CODE_EXPIRED);
        assertThat(codeOf(() -> invitations.openGuestSession(invite.token(), "123456")))
                .isEqualTo(ApiErrorCode.GUEST_CODE_EXPIRED);

        // The owner re-sends the invitation: a fresh link with fresh counters.
        String url = invitations.resend(row.getId(), "alice").invitationUrl();
        String fresh = url.substring(url.lastIndexOf('/') + 1);
        InvitationSignerLifecycleTests.CapturedMailbox.CODES.clear();
        invitations.requestGuestCode(fresh);
        String code = InvitationSignerLifecycleTests.CapturedMailbox.CODES.get(invite.email());
        assertThat(invitations.openGuestSession(fresh, code).workflowId())
                .isEqualTo(invite.workflowId());
    }

    @Test
    void onlyAHashIsStoredAndTheCodeNeverReachesTheLog(CapturedOutput output) {
        Invite invite = invite();
        String code = sendCode(invite);
        InvitationEntity row = row(invite);
        assertThat(row.getGuestCodeHash()).hasSize(64).doesNotContain(code);
        invitations.openGuestSession(invite.token(), code);
        assertThat(row(invite).getGuestCodeHash()).isNull();
        assertThat(output.getAll()).doesNotContain("code " + code).doesNotContain(": " + code);
    }

    @Test
    void codesAreNotIssuedForFinishedOrReplacedInvitations() {
        Invite cancelled = invite();
        workflows.cancelWorkflow(cancelled.workflowId(), "alice");
        assertThat(codeOf(() -> invitations.requestGuestCode(cancelled.token())))
                .isEqualTo(ApiErrorCode.INVITATION_REVOKED);

        Invite replaced = invite();
        invitations.resend(row(replaced).getId(), "alice");
        assertThat(codeOf(() -> invitations.requestGuestCode(replaced.token())))
                .isEqualTo(ApiErrorCode.INVITATION_REVOKED);

        assertThat(codeOf(() -> invitations.requestGuestCode("no-such-invitation-token-00000")))
                .isEqualTo(ApiErrorCode.INVITATION_NOT_FOUND);
    }
}

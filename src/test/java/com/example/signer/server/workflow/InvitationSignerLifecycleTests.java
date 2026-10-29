package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.BindCertificateRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.auth.DbUserAccounts;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.entity.WorkflowSignerEntity;
import com.example.signer.server.db.repository.AuditEventRepository;
import com.example.signer.server.db.repository.InvitationRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.db.repository.WorkflowSignerRepository;
import com.example.signer.server.invites.EmailService;
import com.example.signer.server.invites.InvitationService;
import com.example.signer.server.invites.RegistrationService;
import com.example.signer.server.invites.WorkflowInvitationCoordinator;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invitation → registration/authentication → signing lifecycle (the
 * reported bug): an invitation to an address that has no account must NEVER
 * be reported — or stored — as a signature.
 *
 * <p>Five state axes are checked separately and never substituted for one
 * another:
 *
 * <ol>
 *   <li>invitation state — PENDING / ACCEPTED / REVOKED …</li>
 *   <li>account state — not registered / registered (ACTIVE, authenticable)</li>
 *   <li>authentication — a signing session exists for one participant slot</li>
 *   <li>signer/task state — the slot's explicit lifecycle
 *       (INVITED → PENDING → SIGNED)</li>
 *   <li>signature state — only a server-validated submission flips SIGNED</li>
 * </ol>
 *
 * <p>Runs against live PostgreSQL (skipped, never failed, when unreachable)
 * with an {@link EmailService} that captures invitations instead of sending
 * them, so the whole flow is exercised without a mail server.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-server-lifecycle",
                "signature.remote-server.database.url=${signer.test.db.url:jdbc:postgresql://localhost:5432/signer_test}",
                "signature.remote-server.database.username=${signer.test.db.username:${SIGNER_DB_USERNAME:postgres}}",
                "signature.remote-server.database.password=${signer.test.db.password:${SIGNER_DB_PASSWORD:}}"
        })
@Import(InvitationSignerLifecycleTests.CapturedMailbox.class)
@EnabledIf(value = "com.example.signer.server.workflow.PostgresWorkflowStoreLiveTests#postgresReachable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class InvitationSignerLifecycleTests {

    /** An invitation mailbox that never fails and records what was "sent". */
    @TestConfiguration
    static class CapturedMailbox {

        static final Map<String, String> LINKS = new ConcurrentHashMap<>();

        /** Guest verification codes "sent" (security Phase 3), by address. */
        static final Map<String, String> CODES = new ConcurrentHashMap<>();

        @Bean
        @Primary
        EmailService capturedMailbox() {
            return new EmailService() {
                @Override
                public void sendSigningInvitation(String recipientEmail, String recipientName,
                                                  String workflowName, String inviterName,
                                                  String invitationUrl, java.time.Instant expiresAt) {
                    LINKS.put(recipientEmail, invitationUrl);
                }

                @Override
                public void sendGuestVerificationCode(String recipientEmail, String workflowName,
                                                      String code, java.time.Instant expiresAt) {
                    CODES.put(recipientEmail, code);
                }
            };
        }
    }

    @Autowired
    private com.example.signer.server.invites.InvitationRestController invitationController;

    @Autowired
    private WorkflowInvitationCoordinator coordinator;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private RemoteWorkflowService workflows;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowSignerRepository signerRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditEventRepository auditRepository;

    @Autowired
    private DbUserAccounts accounts;

    @Autowired
    private WorkflowSigningService workflowSigningService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @AfterEach
    void tearDown() {
        tokenService.disconnect();
        simulator.removeToken();
        CapturedMailbox.LINKS.clear();
    }

    /* ══════════════════════════════════════════════════════════════════
     * TEST 1 — inviting an email that has no account
     * ══════════════════════════════════════════════════════════════════ */

    @Test
    void test1_invitingAnUnknownEmailSignsNothingAndRegistersNobody() {
        Invite fixture = inviteByEmail(freshEmail());

        // invitation: PENDING (sent) — invitation state
        InvitationEntity invitation = invitations.redeemableByToken(fixture.token());
        assertThat(invitation.getStatus()).isEqualTo(InvitationEntity.STATUS_PENDING);

        // user/account: NOT registered — the invited person cannot
        // authenticate, and no signature exists anywhere.
        assertThat(accounts.isRegistered(fixture.slotUsername())).isFalse();
        assertThat(accounts.matches(fixture.email(), "anything")).isFalse();

        // signer/task: INVITED, unsigned; workflow incomplete
        TaskView.StepView step = onlyStep(
                workflows.getTask(fixture.workflowId(), "alice"));
        assertThat(step.signed()).isFalse();
        assertThat(step.signerState()).isEqualTo("INVITED");

        // signature/completion: nothing signed, workflow not complete
        WorkflowView view = workflows.getWorkflow(fixture.workflowId(), "alice");
        assertThat(view.completedSignatures()).isZero();
        assertThat(view.status()).isNotEqualTo("COMPLETED");
    }

    /**
     * The reported reproduction: inviting a stranger's address into a slot
     * that already belongs to someone else. That must be refused (the slot's
     * own recipient is the only address the invitation may carry), otherwise
     * the stranger is told they already have an account and the invited
     * person can never sign.
     */
    @Test
    void test1b_invitingAStrangerIntoSomeoneElsesSlotIsRefused() {
        // A workflow whose single slot belongs to the existing account bob
        // (bound certificate), invited as a normal account slot.
        PlainWorkflow prepared = createPlainUsernameWorkflow("bob");
        assertThatThrownBy(() -> invitations.invite(prepared.workflowId(), 1,
                freshEmail(), "alice"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("assigned to");

        assertThat(signers(prepared.workflowId()).get(0).isSigned()).isFalse();
    }

    /* ══════════════════════════════════════════════════════════════════
     * TEST 2 — recipient opens the invitation without an account
     * ══════════════════════════════════════════════════════════════════ */

    @Test
    void test2_openingTheInvitationWithoutAnAccountKeepsTheSignerPending() {
        Invite fixture = inviteByEmail(freshEmail());

        // Opening the invitation only validates it (no mutation, no session).
        assertThat(invitations.redeemableByToken(fixture.token()).getStatus())
                .isEqualTo(InvitationEntity.STATUS_PENDING);

        // Registration is still required, and the slot stays unsigned.
        assertThat(accounts.isRegistered(fixture.slotUsername())).isFalse();
        assertThat(onlyStep(workflows.getTask(fixture.workflowId(),
                "alice")).signed()).isFalse();
        assertThat(workflows.getWorkflow(fixture.workflowId(), "alice").status())
                .isNotEqualTo("COMPLETED");
    }

    /* ══════════════════════════════════════════════════════════════════
     * TEST 3 — recipient registers from the invitation
     * ══════════════════════════════════════════════════════════════════ */

    @Test
    void test3_registrationActivatesTheAccountButSignsNothing() {
        Invite fixture = inviteByEmail(freshEmail());

        String account = registrationService.register(fixture.token(),
                "New Signer", "password-123");

        // account: REGISTERED (authenticable)
        assertThat(account).isEqualTo(fixture.slotUsername());
        assertThat(accounts.isRegistered(account)).isTrue();
        assertThat(accounts.matches(account, "password-123")).isTrue();
        // and the invitation is associated with that account
        assertThat(invitationOf(fixture).getAcceptedBy()).isEqualTo(account);

        // signer: still PENDING, never SIGNED
        TaskView.StepView step = onlyStep(
                workflows.getTask(fixture.workflowId(), "alice"));
        assertThat(step.signed()).isFalse();
        assertThat(step.signerState()).isEqualTo("PENDING");

        // signature/completion: unchanged
        WorkflowView view = workflows.getWorkflow(fixture.workflowId(), "alice");
        assertThat(view.completedSignatures()).isZero();
        assertThat(view.status()).isNotEqualTo("COMPLETED");
        assertThat(signers(fixture.workflowId())).allSatisfy(s ->
                assertThat(s.isSigned()).isFalse());

        // the participant's own task list shows an unsigned task, not a
        // completed one
        assertThat(workflows.listParticipating(account).tasks())
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.completedSignatures()).isZero();
                    assertThat(t.myState().signed()).isFalse();
                    assertThat(t.myState().myStateLabel()).isNotEqualTo("SIGNED");
                });

        // the registration is audited as registration, not as a signature
        assertThat(auditTypes(fixture.workflowId()))
                .contains("SIGNER_REGISTERED")
                .doesNotContain("SIGNER_COMPLETED", "WORKFLOW_COMPLETED");
    }

    /* ══════════════════════════════════════════════════════════════════
     * TEST 4 — recipient logs in through the invitation
     * ══════════════════════════════════════════════════════════════════ */

    @Test
    void test4_loggingInThroughTheInvitationOpensTheTaskWithoutSigning() {
        Invite fixture = inviteByEmail(freshEmail());
        String account = registrationService.register(fixture.token(),
                "New Signer", "password-123");

        // Registration already associated the account with the invitation and
        // opened its participant session, so claiming is the idempotent
        // reopen path here — and still signs nothing.
        var session = invitations.claim(fixture.token(), account);
        assertThat(session.workflowId()).isEqualTo(fixture.workflowId());
        assertThat(session.participantOrder()).isEqualTo(1);
        assertThat(session.alreadyClaimed()).isTrue();
        assertThat(invitationOf(fixture).getStatus())
                .isEqualTo(InvitationEntity.STATUS_ACCEPTED);
        assertThat(invitationOf(fixture).getAcceptedBy()).isEqualTo(account);
        // reopening the invitation repeatedly stays idempotent
        assertThat(invitations.claim(fixture.token(), account).alreadyClaimed()).isTrue();

        TaskView task = workflows.getTask(fixture.workflowId(), account);
        assertThat(task.signerOrder()).isEqualTo(1);
        assertThat(onlyStep(task).signed()).isFalse();
        assertThat(onlyStep(task).signerState()).isEqualTo("PENDING");
    }

    /* ══════════════════════════════════════════════════════════════════
     * TEST 5 — recipient opens/views the document
     * ══════════════════════════════════════════════════════════════════ */

    @Test
    void test5_viewingTheDocumentDoesNotSign() throws Exception {
        Invite fixture = inviteByEmail(freshEmail());
        String account = registrationService.register(fixture.token(),
                "New Signer", "password-123");

        // enrolment: the signer binds their own certificate (their machine).
        // Only now can the document exist (revision 0).
        WorkflowView bound = workflows.bindCertificate(fixture.workflowId(), account,
                new BindCertificateRequest(fingerprint(MockSigningToken.DEFAULT_ALIAS)));
        assertThat(bound.status()).isEqualTo("WAITING_FOR_SIGNER");

        byte[] document = workflows.getDocument(fixture.workflowId(), account);
        assertThat(document).isNotEmpty();

        // Downloading/reading the document is not signing.
        assertThat(onlyStep(workflows.getTask(fixture.workflowId(), account)).signed())
                .isFalse();
        assertThat(workflows.getWorkflow(fixture.workflowId(), "alice").status())
                .isEqualTo("WAITING_FOR_SIGNER");
        assertThat(workflows.getWorkflow(fixture.workflowId(), "alice")
                .completedSignatures()).isZero();
    }

    /* ══════════════════════════════════════════════════════════════════
     * TEST 6 — recipient actually signs
     * ══════════════════════════════════════════════════════════════════ */

    @Test
    void test6_onlyAnAcceptedSignatureMarksTheSignerSigned() throws Exception {
        Invite fixture = inviteByEmail(freshEmail());
        String account = registrationService.register(fixture.token(),
                "New Signer", "password-123");
        workflows.bindCertificate(fixture.workflowId(), account,
                new BindCertificateRequest(fingerprint(MockSigningToken.DEFAULT_ALIAS)));

        String opId = "lifecycle-sign-" + UUID.randomUUID();
        byte[] signedPdf = signLocally(workflows.getDocument(fixture.workflowId(),
                account), 1);

        // The PREVIOUS state must already be unsigned, so the transition is
        // unambiguous.
        assertThat(onlyStep(workflows.getTask(fixture.workflowId(), account)).signed())
                .isFalse();

        SignatureSubmissionResponse response = workflows.submitSignature(
                fixture.workflowId(), account, signedPdf,
                new SignatureSubmissionRequest(0, null, opId));

        assertThat(response.acceptedRevision()).isEqualTo(1);
        assertThat(response.completed()).isTrue();

        TaskView.StepView step = onlyStep(
                workflows.getTask(fixture.workflowId(), account));
        assertThat(step.signed()).isTrue();
        assertThat(step.signerState()).isEqualTo("SIGNED");

        WorkflowSignerEntity row = signers(fixture.workflowId()).get(0);
        assertThat(row.isSigned()).isTrue();
        assertThat(row.getSignedAt()).isNotNull();
        assertThat(row.getSigningOperationId()).isEqualTo(opId);

        assertThat(workflows.getWorkflow(fixture.workflowId(), "alice").status())
                .isEqualTo("COMPLETED");
        assertThat(auditTypes(fixture.workflowId()))
                .contains("SIGNATURE_VALIDATED", "SIGNER_COMPLETED", "WORKFLOW_COMPLETED");

        // Re-opening the invitation link now says "completed", not "used".
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> invitationController.preview(fixture.token()))
                .isInstanceOfSatisfying(com.example.signer.server.api.ApiErrorType.ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(
                                com.example.signer.server.api.ApiErrorType.ApiErrorCode.INVITATION_COMPLETED));
    }

    /* ══════════════════════════════════════════════════════════════════
     * TEST 7 — two signers sign independently
     * ══════════════════════════════════════════════════════════════════ */

    @Test
    void test7_twoSignersSignIndependentlyAndCompleteOnlyTogether() throws Exception {
        String invited = freshEmail();
        // FLEXIBLE: any pending signer may hold the turn, so neither signer's
        // signature is paced by the other's slot order.
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("lifecycle-two.pdf", "FLEXIBLE",
                        List.of(new CreateWorkflowRequest.SignerSpec(1, "alice",
                                        fingerprint(MockSigningToken.DEFAULT_ALIAS)),
                                new CreateWorkflowRequest.SignerSpec(2, invited, null))),
                "alice");
        WorkflowView created = workflows.createWorkflow(prep.request(), minimalPdf(), "alice");
        List<InvitationLinkView> sent = coordinator.invite(created.workflowId(),
                prep.pendingInvites(), "alice");
        String token = tokenOf(sent.get(0).invitationUrl());
        String account = registrationService.register(token, "Second Signer",
                "password-123");
        workflows.bindCertificate(created.workflowId(), account,
                new BindCertificateRequest(fingerprint(MockSigningToken.THIRD_ALIAS)));

        String id = created.workflowId();

        // ── first signer: claim → sign → accepted independently ─────────
        workflows.claimTurn(id, "alice");
        TaskView aliceTask = workflows.getTask(id, "alice");
        byte[] aliceSigned = signLocally(workflows.getDocument(id, "alice"),
                aliceTask.signerOrder());
        SignatureSubmissionResponse first = workflows.submitSignature(id, "alice",
                aliceSigned, new SignatureSubmissionRequest(0, null,
                        "lifecycle-7-a-" + UUID.randomUUID()));
        assertThat(first.acceptedRevision()).isEqualTo(1);
        assertThat(first.completed()).isFalse();

        // the OTHER signer is still unsigned, and the workflow is incomplete
        TaskView afterFirst = workflows.getTask(id, account);
        assertThat(afterFirst.signers()).filteredOn(TaskView.StepView::signed)
                .singleElement()
                .satisfies(s -> assertThat(s.order()).isEqualTo(1));
        assertThat(afterFirst.signers()).filteredOn(s -> s.order() == 2)
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.signed()).isFalse();
                    assertThat(s.signerState()).isEqualTo("PENDING");
                });
        assertThat(workflows.getWorkflow(id, "alice").status())
                .isEqualTo("WAITING_FOR_SIGNER");

        // ── second signer: sign → both accepted → now complete ──────────
        workflows.claimTurn(id, account);
        TaskView secondTask = workflows.getTask(id, account);
        byte[] secondSigned = signLocally(workflows.getDocument(id, account),
                secondTask.signerOrder());
        SignatureSubmissionResponse second = workflows.submitSignature(id, account,
                secondSigned, new SignatureSubmissionRequest(1, null,
                        "lifecycle-7-b-" + UUID.randomUUID()));
        assertThat(second.acceptedRevision()).isEqualTo(2);
        assertThat(second.completed()).isTrue();

        // exactly one signature per signer, no duplicates, distinct ops
        List<WorkflowSignerEntity> rows = signers(id);
        assertThat(rows).hasSize(2).allSatisfy(s -> assertThat(s.isSigned()).isTrue());
        assertThat(rows).extracting(WorkflowSignerEntity::getSigningOperationId)
                .doesNotContainNull()
                .doesNotHaveDuplicates();
        assertThat(workflows.getRevisions(id, "alice"))
                .extracting(com.example.signer.server.api.ApiContracts.RevisionView::revisionNumber)
                .containsExactly(0, 1, 2);
    }

    /* ══════════════════════════════════════════════════════════════════
     * TEST 8 — double-click / retry of the same submission
     * ══════════════════════════════════════════════════════════════════ */

    @Test
    void test8_retryingTheSameSubmissionIsIdempotent() throws Exception {
        Invite fixture = inviteByEmail(freshEmail());
        String account = registrationService.register(fixture.token(),
                "New Signer", "password-123");
        workflows.bindCertificate(fixture.workflowId(), account,
                new BindCertificateRequest(fingerprint(MockSigningToken.DEFAULT_ALIAS)));

        String opId = "lifecycle-8-" + UUID.randomUUID();
        byte[] signedPdf = signLocally(workflows.getDocument(fixture.workflowId(),
                account), 1);
        SignatureSubmissionResponse first = workflows.submitSignature(
                fixture.workflowId(), account, signedPdf,
                new SignatureSubmissionRequest(0, null, opId));
        Instant signedAt = signers(fixture.workflowId()).get(0).getSignedAt();

        // Retry with the SAME operation id (and an intentionally wrong parent
        // revision, as an out-of-order retry would carry): still a replay.
        SignatureSubmissionResponse replay = workflows.submitSignature(
                fixture.workflowId(), account, signedPdf,
                new SignatureSubmissionRequest(0, null, opId));
        assertThat(replay.acceptedRevision()).isEqualTo(first.acceptedRevision());

        // exactly one accepted revision, one signer row, one signature
        assertThat(workflows.getRevisions(fixture.workflowId(), "alice"))
                .hasSize(2);
        assertThat(signers(fixture.workflowId())).hasSize(1);
        assertThat(signers(fixture.workflowId()).get(0).getSignedAt())
                .isEqualTo(signedAt);
        assertThat(auditTypes(fixture.workflowId()))
                .contains("DUPLICATE_OPERATION_IGNORED");
    }

    /* ══════════════════════════════════════════════════════════════════
     * TEST 9 — unauthenticated / unauthorized submission
     * ══════════════════════════════════════════════════════════════════ */

    @Test
    void test9_aSubmissionFromSomeoneElseNeverSigns() throws Exception {
        Invite fixture = inviteByEmail(freshEmail());
        String account = registrationService.register(fixture.token(),
                "New Signer", "password-123");
        workflows.bindCertificate(fixture.workflowId(), account,
                new BindCertificateRequest(fingerprint(MockSigningToken.DEFAULT_ALIAS)));

        // An authenticated user who is not a participant at all: the server
        // refuses before touching the document.
        String opId = "lifecycle-9-" + UUID.randomUUID();
        byte[] signedPdf = signLocally(workflows.getDocument(fixture.workflowId(),
                account), 1);
        assertThatThrownBy(() -> workflows.submitSignature(fixture.workflowId(),
                "charlie", signedPdf, new SignatureSubmissionRequest(0, null, opId)))
                .isInstanceOf(ApiException.class);

        // A participant whose turn it is not (the creator is not a signer
        // here, so use the workflow creator path only after the fact): the
        // signer slot is still unsigned.
        assertThat(signers(fixture.workflowId())).allSatisfy(s ->
                assertThat(s.isSigned()).isFalse());
        assertThat(onlyStep(workflows.getTask(fixture.workflowId(), account)).signed())
                .isFalse();
        // and the refused submission left no revision behind
        assertThat(workflows.getRevisions(fixture.workflowId(), "alice"))
                .hasSize(1);
    }

    /* ══════════════════════════════════════════════════════════════════
     * TEST 10 — authenticated user who is NOT the invited recipient
     * ══════════════════════════════════════════════════════════════════ */

    @Test
    void test10_anAccountThatIsNotTheRecipientCannotUseTheInvitation() {
        Invite fixture = inviteByEmail(freshEmail());

        // bob is a registered, authenticated account — just not the recipient.
        assertThatThrownBy(() -> invitations.claim(fixture.token(), "bob"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("different email");
        assertThatThrownBy(() -> invitations.accept(fixture.token(), "bob"))
                .isInstanceOf(ApiException.class);

        // nothing was consumed and nothing was signed
        assertThat(invitations.redeemableByToken(fixture.token()).getStatus())
                .isEqualTo(InvitationEntity.STATUS_PENDING);
        assertThat(signers(fixture.workflowId())).allSatisfy(s ->
                assertThat(s.isSigned()).isFalse());

        // the real recipient can still use it
        String account = registrationService.register(fixture.token(),
                "New Signer", "password-123");
        assertThat(invitations.claim(fixture.token(), account).participantOrder())
                .isEqualTo(1);
    }

    /* ══════════════════════════════════════════════════════════════════
     * fixture / helpers
     * ══════════════════════════════════════════════════════════════════ */

    private record Invite(String workflowId, String email, String slotUsername,
                          String token) {
    }

    private record PlainWorkflow(String workflowId) {
    }

    /** Creates a workflow whose only signer is an invited email address. */
    private Invite inviteByEmail(String email) {
        WorkflowInvitationCoordinator.Preparation prep = coordinator.prepare(
                new CreateWorkflowRequest("lifecycle-one.pdf", "SEQUENTIAL",
                        List.of(new CreateWorkflowRequest.SignerSpec(1, email, null))),
                "alice");
        WorkflowView view = workflows.createWorkflow(prep.request(), minimalPdf(), "alice");
        List<InvitationLinkView> sent = coordinator.invite(view.workflowId(),
                prep.pendingInvites(), "alice");
        assertThat(sent).hasSize(1);
        return new Invite(view.workflowId(),
                prep.pendingInvites().get(0).email(),
                prep.pendingInvites().get(0).username(),
                tokenOf(sent.get(0).invitationUrl()));
    }

    /** A workflow whose single slot belongs to an existing plain account. */
    private PlainWorkflow createPlainUsernameWorkflow(String username) {
        WorkflowView view = workflows.createWorkflow(new CreateWorkflowRequest(
                "lifecycle-owner.pdf", "SEQUENTIAL",
                List.of(new CreateWorkflowRequest.SignerSpec(1, username, null))),
                minimalPdf(), "alice");
        return new PlainWorkflow(view.workflowId());
    }

    private static TaskView.StepView onlyStep(TaskView task) {
        assertThat(task.signers()).hasSize(1);
        return task.signers().get(0);
    }

    private List<WorkflowSignerEntity> signers(String workflowPublicId) {
        Long id = workflowRepository.findByWorkflowId(workflowPublicId)
                .orElseThrow().getId();
        return signerRepository.findByWorkflowIdOrderBySignerOrder(id);
    }

    private InvitationEntity invitationOf(Invite fixture) {
        Long id = workflowRepository.findByWorkflowId(fixture.workflowId())
                .orElseThrow().getId();
        return invitationRepository.findByWorkflowId(id).stream()
                .max(java.util.Comparator.comparing(InvitationEntity::getId))
                .orElseThrow();
    }

    private List<String> auditTypes(String workflowPublicId) {
        Long id = workflowRepository.findByWorkflowId(workflowPublicId)
                .orElseThrow().getId();
        return auditRepository.findByWorkflowIdOrderByEventSeq(id).stream()
                .map(e -> e.getEventType())
                .toList();
    }

    /** One desktop signing session: download → sign locally → bytes. */
    private byte[] signLocally(byte[] documentBytes, Integer signerOrder)
            throws Exception {
        Path dir = Files.createTempDirectory("lifecycle-sign");
        Path downloaded = dir.resolve("download.pdf");
        Files.write(downloaded, documentBytes);
        connectToken();
        Path signed = dir.resolve("signed.pdf");
        workflowSigningService.signNextStep(downloaded, null, signed, signerOrder);
        return Files.readAllBytes(signed);
    }

    private String fingerprint(String alias) {
        connectToken();
        String fp = tokenService.getCertificates().stream()
                .filter(c -> alias.equals(c.getAlias()))
                .map(SigningCertificate::getSha256Fingerprint)
                .findFirst().orElseThrow();
        tokenService.disconnect();
        simulator.removeToken();
        return fp;
    }

    private void connectToken() {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());
    }

    private static String tokenOf(String invitationUrl) {
        return invitationUrl.substring(invitationUrl.lastIndexOf('/') + 1);
    }

    private static String freshEmail() {
        return "lifecycle" + System.nanoTime() + "@example.test";
    }

    private static byte[] minimalPdf() {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("test PDF", e);
        }
    }
}

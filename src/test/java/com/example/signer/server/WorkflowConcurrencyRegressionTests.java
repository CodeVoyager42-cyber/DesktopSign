package com.example.signer.server;

import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.RevisionView;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.verification.PdfVerificationService;
import com.example.signer.workflow.WorkflowSigningPolicy;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0.4 / P0.6 / P0.8 / P0.14 / P0.15 regression tests for the workflow
 * mutation protocol (lock → reload inside the lock → validate → mutate →
 * persist), run against the file-backed store.
 *
 * <p>Concurrent tests release every thread through one start gate so the
 * operations really overlap; each scenario then checks the INVARIANTS that
 * must hold whatever the interleaving: exactly one transition wins, the
 * revision chain stays contiguous, terminal states never change and nothing
 * accepted is lost.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkflowConcurrencyRegressionTests {

    /** Codes a losing request may legitimately get (never a 5xx). */
    private static final Set<ApiErrorCode> CONFLICT_CODES = Set.of(
            ApiErrorCode.NOT_YOUR_TURN, ApiErrorCode.INVALID_WORKFLOW_STATE,
            ApiErrorCode.STALE_REVISION, ApiErrorCode.REVISION_CONFLICT,
            ApiErrorCode.SIGNATURE_FIELD_ALREADY_SIGNED, ApiErrorCode.TURN_ALREADY_CLAIMED,
            ApiErrorCode.WORKFLOW_CONFLICT);

    @Autowired
    private RemoteWorkflowService service;

    @Autowired
    private WorkflowFileStore store;

    @Autowired
    private WorkflowSigningService workflowSigningService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @Autowired
    private ApplicationContext context;

    @AfterEach
    void cleanUp() {
        tokenService.disconnect();
        simulator.removeToken();
    }

    /* ── concurrent signing ──────────────────────────────────────────── */

    @Test
    void sameSignerSubmittingTwiceSimultaneouslyCreatesExactlyOneRevision() throws Exception {
        String id = create(WorkflowSigningPolicy.SEQUENTIAL, "alice", "bob");
        byte[] signed = signLocally(id, "alice", 1);

        List<Outcome> outcomes = race(4, i -> () -> service.submitSignature(id, "alice",
                signed, new SignatureSubmissionRequest(0, null, "op-dup-" + i)));

        assertThat(outcomes.stream().filter(Outcome::ok)).hasSize(1);
        outcomes.stream().filter(o -> !o.ok()).forEach(o -> assertConflict(o.error()));
        assertRevisions(id, 0, 1);
        assertThat(service.getWorkflow(id, "alice").status()).isEqualTo("WAITING_FOR_SIGNER");
        assertThat(store.load(id).signers().get(0).signed()).isTrue();
        assertThat(store.load(id).signers().get(1).signed()).isFalse();
    }

    @Test
    void duplicateSubmissionsWithTheSameOperationIdAllReturnTheOriginalRevision()
            throws Exception {
        String id = create(WorkflowSigningPolicy.SEQUENTIAL, "alice", "bob");
        byte[] signed = signLocally(id, "alice", 1);

        List<Outcome> outcomes = race(4, i -> () -> service.submitSignature(id, "alice",
                signed, new SignatureSubmissionRequest(0, null, "op-same")));

        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.ok()).as("replay must succeed: %s", o.error()).isTrue();
            assertThat(o.response().acceptedRevision()).isEqualTo(1);
        });
        assertThat(outcomes.stream().map(o -> o.response().acceptedRevision()).distinct())
                .containsExactly(1);
        assertRevisions(id, 0, 1);
    }

    @Test
    void twoFlexibleSignersSubmittingSimultaneouslyCommitExactlyOneTransition()
            throws Exception {
        String id = create(WorkflowSigningPolicy.FLEXIBLE, "alice", "bob");
        // Both signers hold a locally signed copy of revision 0 (e.g. one of
        // them downloaded it under a claim that later expired).
        byte[] aliceSigned = signStoredRevision(id, 0, 1);
        byte[] bobSigned = signStoredRevision(id, 0, 2);
        Map<String, byte[]> bytes = Map.of("alice", aliceSigned, "bob", bobSigned);
        List<String> users = List.of("alice", "bob");

        List<Outcome> outcomes = race(2, i -> () -> {
            String user = users.get(i);
            service.claimTurn(id, user);
            return service.submitSignature(id, user, bytes.get(user),
                    new SignatureSubmissionRequest(0, null, "op-flex-" + user));
        });

        assertThat(outcomes.stream().filter(Outcome::ok)).hasSize(1);
        outcomes.stream().filter(o -> !o.ok()).forEach(o -> assertConflict(o.error()));
        assertRevisions(id, 0, 1);
        long signedSlots = store.load(id).signers().stream()
                .filter(WorkflowSignerRecord::signed).count();
        assertThat(signedSlots).isEqualTo(1);
    }

    @Test
    void submitWhileAnotherSignerClaimsNeverLosesTheAcceptedSignature() throws Exception {
        String id = create(WorkflowSigningPolicy.FLEXIBLE, "alice", "bob");
        service.claimTurn(id, "alice");
        byte[] aliceSigned = signLocally(id, "alice", 1);

        List<Outcome> outcomes = race(2, i -> i == 0
                ? () -> service.submitSignature(id, "alice", aliceSigned,
                        new SignatureSubmissionRequest(0, null, "op-a"))
                : () -> service.claimTurn(id, "bob"));

        assertThat(outcomes.get(0).ok()).as("alice holds the turn: %s",
                outcomes.get(0).error()).isTrue();
        if (!outcomes.get(1).ok()) {
            assertConflict(outcomes.get(1).error());
        }
        SigningWorkflowAggregate wf = store.load(id);
        assertThat(wf.signers().get(0).signed()).isTrue();
        assertRevisions(id, 0, 1);
        // Bob can (still) take the turn now that alice is done.
        assertThat(service.claimTurn(id, "bob").signerOrder()).isEqualTo(2);
    }

    @Test
    void submitRacingCancelLeavesExactlyOneConsistentTerminalState() throws Exception {
        for (int round = 0; round < 4; round++) {
            String id = create(WorkflowSigningPolicy.SEQUENTIAL, "alice");
            byte[] signed = signLocally(id, "alice", 1);
            final int r = round;

            List<Outcome> outcomes = race(2, i -> i == (r % 2)
                    ? () -> service.submitSignature(id, "alice", signed,
                            new SignatureSubmissionRequest(0, null, "op-race-" + r))
                    : () -> service.cancelWorkflow(id, "alice"));

            assertThat(outcomes.stream().filter(Outcome::ok)).hasSize(1);
            outcomes.stream().filter(o -> !o.ok()).forEach(o -> assertConflict(o.error()));
            String status = service.getWorkflow(id, "alice").status();
            if ("COMPLETED".equals(status)) {
                assertRevisions(id, 0, 1);
                assertThat(store.load(id).allSigned()).isTrue();
            } else {
                assertThat(status).isEqualTo("CANCELLED");
                assertRevisions(id, 0);
                assertThat(store.load(id).signers().get(0).signed()).isFalse();
            }
        }
    }

    @Test
    void aStaleSubmitNeverReplacesTheAcceptedRevision() throws Exception {
        String id = create(WorkflowSigningPolicy.SEQUENTIAL, "alice", "bob");
        byte[] first = signLocally(id, "alice", 1);
        service.submitSignature(id, "alice", first,
                new SignatureSubmissionRequest(0, null, "op-first"));
        String acceptedSha = store.load(id).currentRevision().sha256();

        // A second, different signature of the SAME parent (an old window,
        // a re-sign after a crash) arrives later with a new operation id.
        byte[] stale = signRevisionZeroAgain(id);
        assertThatThrownBy(() -> service.submitSignature(id, "alice", stale,
                new SignatureSubmissionRequest(0, null, "op-stale")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertConflict((ApiException) e));

        assertRevisions(id, 0, 1);
        assertThat(store.load(id).currentRevision().sha256())
                .isEqualTo(acceptedSha);
    }

    /* ── cancellation authorization and terminal states ──────────────── */

    @Test
    void onlyTheOwnerCanCancel() throws Exception {
        String id = createOwnedBy("charlie", "alice", "bob");

        ApiException bySigner = catchApi(() -> service.cancelWorkflow(id, "bob"));
        assertThat(bySigner.code()).isEqualTo(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE);
        assertThat(httpStatus(bySigner)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(catchApi(() -> service.cancelWorkflow(id, "alice")).code())
                .isEqualTo(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE);
        assertThat(catchApi(() -> service.cancelWorkflow(id, "mallory")).code())
                .isIn(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE, ApiErrorCode.WORKFLOW_NOT_FOUND);
        assertThat(service.getWorkflow(id, "charlie").status()).isEqualTo("WAITING_FOR_SIGNER");

        assertThat(service.cancelWorkflow(id, "charlie").status()).isEqualTo("CANCELLED");
    }

    @Test
    void aCancelledWorkflowNeverBecomesActiveAgain() throws Exception {
        String id = create(WorkflowSigningPolicy.SEQUENTIAL, "alice", "bob");
        byte[] signed = signLocally(id, "alice", 1);
        service.cancelWorkflow(id, "alice");

        assertThat(catchApi(() -> service.submitSignature(id, "alice", signed,
                new SignatureSubmissionRequest(0, null, "op-after-cancel"))).code())
                .isIn(CONFLICT_CODES.toArray(ApiErrorCode[]::new));
        ApiException again = catchApi(() -> service.cancelWorkflow(id, "alice"));
        assertThat(again.code()).isEqualTo(ApiErrorCode.INVALID_WORKFLOW_STATE);
        assertThat(httpStatus(again)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(service.getWorkflow(id, "alice").status()).isEqualTo("CANCELLED");
        assertRevisions(id, 0);
    }

    @Test
    void aStaleCancelAfterCompletionLeavesTheWorkflowCompleted() throws Exception {
        String id = create(WorkflowSigningPolicy.SEQUENTIAL, "alice");
        // The owner's window still shows "waiting" when the signature lands.
        SigningWorkflowAggregate staleView = store.load(id);
        byte[] signed = signLocally(id, "alice", 1);
        service.submitSignature(id, "alice", signed,
                new SignatureSubmissionRequest(0, null, "op-complete"));
        assertThat(staleView.status()).isEqualTo(WorkflowState.WAITING_FOR_SIGNER);

        ApiException cancel = catchApi(() -> service.cancelWorkflow(id, "alice"));
        assertThat(cancel.code()).isEqualTo(ApiErrorCode.INVALID_WORKFLOW_STATE);
        assertThat(service.getWorkflow(id, "alice").status()).isEqualTo("COMPLETED");

        // Even a direct stale write of the old aggregate is refused.
        staleView.transition(WorkflowState.CANCELLED);
        assertThatThrownBy(() -> store.save(staleView))
                .isInstanceOf(WorkflowConflictException.class);
        assertThat(service.getWorkflow(id, "alice").status()).isEqualTo("COMPLETED");
        assertRevisions(id, 0, 1);
    }

    /* ── persistence ─────────────────────────────────────────────────── */

    @Test
    void aStaleAggregateWriteIsRejectedInsteadOfOverwritingNewerState() throws Exception {
        String id = create(WorkflowSigningPolicy.SEQUENTIAL, "alice", "bob");
        SigningWorkflowAggregate first = store.load(id);
        SigningWorkflowAggregate second = store.load(id);

        first.appendAudit(AuditEventType.WORKFLOW_CREATED, "alice", "first writer");
        store.save(first);

        second.appendAudit(AuditEventType.WORKFLOW_CREATED, "bob", "stale writer");
        assertThatThrownBy(() -> store.save(second))
                .isInstanceOf(WorkflowConflictException.class);
        assertThat(httpStatus(new WorkflowConflictException("x"))).isEqualTo(HttpStatus.CONFLICT);

        List<String> details = store.load(id).audit().stream()
                .map(AuditEventRecord::detail).toList();
        assertThat(details).contains("first writer").doesNotContain("stale writer");
    }

    @Test
    void auditSequenceStaysUniqueAndContiguousUnderConcurrentWrites() throws Exception {
        String id = create(WorkflowSigningPolicy.FLEXIBLE, "alice", "bob");
        List<Outcome> outcomes = race(8, i -> () -> {
            synchronized (store.lockFor(id)) {
                SigningWorkflowAggregate wf = store.load(id);
                wf.appendAudit(AuditEventType.WORKFLOW_CREATED, "alice", "note " + i);
                store.save(wf);
            }
            return null;
        });
        assertThat(outcomes).allSatisfy(o -> assertThat(o.ok()).isTrue());
        List<Long> seqs = store.load(id).audit().stream()
                .map(AuditEventRecord::sequence).toList();
        assertThat(seqs).doesNotHaveDuplicates();
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i + 1L);
        }
        assertThat(store.load(id).audit().stream().map(AuditEventRecord::detail))
                .contains("note 0", "note 7");
    }

    @Test
    void aStoreFailureAfterTheRevisionFileWriteLeavesNoOrphanAndCanBeRetried()
            throws Exception {
        String id = create(WorkflowSigningPolicy.SEQUENTIAL, "alice", "bob");
        byte[] signed = signLocally(id, "alice", 1);

        FailingStore failing = new FailingStore(store);
        RemoteWorkflowService failingService = new RemoteWorkflowService(failing,
                context.getBean(RemoteServerProperties.class),
                context.getBean(PdfVerificationService.class),
                workflowSigningService,
                context.getBean(SignaturePlacementExtractor.class),
                context.getBeanProvider(com.example.signer.server.auth.UserAccountService.class),
                context.getBeanProvider(WorkflowDirectory.class),
                context.getBeanProvider(com.example.signer.server.invites.InvitationService.class));

        failing.failSaveAfterRevisionWrite = true;
        assertThatThrownBy(() -> failingService.submitSignature(id, "alice", signed,
                new SignatureSubmissionRequest(0, null, "op-db-down")))
                .hasMessageContaining("simulated database failure");

        assertThat(failing.revisionWrites).isEqualTo(1);
        assertThat(store.existsRevision(id, 1)).as("orphan revision file removed").isFalse();
        assertRevisions(id, 0);
        assertThat(store.load(id).signers().get(0).signed()).isFalse();

        // Database back: the client's idempotent retry (same op id) succeeds once.
        failing.failSaveAfterRevisionWrite = false;
        SignatureSubmissionResponse ok = failingService.submitSignature(id, "alice", signed,
                new SignatureSubmissionRequest(0, null, "op-db-down"));
        assertThat(ok.acceptedRevision()).isEqualTo(1);
        assertRevisions(id, 0, 1);
    }

    @Test
    void theRegressionGuardRefusesTerminalAndSignedStateRollback() {
        SigningWorkflowAggregate completed = aggregate(WorkflowState.COMPLETED, true);
        SigningWorkflowAggregate cancelled = aggregate(WorkflowState.CANCELLED, true);
        SigningWorkflowAggregate active = aggregate(WorkflowState.WAITING_FOR_SIGNER, false);
        SigningWorkflowAggregate activeSigned = aggregate(WorkflowState.WAITING_FOR_SIGNER, true);

        assertThatThrownBy(() -> WorkflowRegressionGuard.check(completed, cancelled))
                .isInstanceOf(WorkflowConflictException.class);
        assertThatThrownBy(() -> WorkflowRegressionGuard.check(cancelled, activeSigned))
                .isInstanceOf(WorkflowConflictException.class);
        assertThatThrownBy(() -> WorkflowRegressionGuard.check(activeSigned, active))
                .isInstanceOf(WorkflowConflictException.class);
        // Legal progress passes.
        WorkflowRegressionGuard.check(active, activeSigned);
    }

    /* ── helpers ─────────────────────────────────────────────────────── */

    private record Outcome(SignatureSubmissionResponse response, ApiException error,
                           boolean ok) {
    }

    private interface TaskFactory {
        Callable<Object> task(int index);
    }

    /** Runs {@code n} tasks released together; returns outcomes in task order. */
    private static List<Outcome> race(int n, TaskFactory factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<Object> task = factory.task(i);
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                try {
                    Object value = f.get(60, TimeUnit.SECONDS);
                    outcomes.add(new Outcome(value instanceof SignatureSubmissionResponse r
                            ? r : null, null, true));
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof ApiException api) {
                        outcomes.add(new Outcome(null, api, false));
                    } else {
                        throw new AssertionError("unexpected failure (not a typed API"
                                + " conflict): " + e.getCause(), e.getCause());
                    }
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertConflict(ApiException e) {
        assertThat(e.code()).as("losing request code (%s)", e.getMessage())
                .isIn(CONFLICT_CODES.toArray(ApiErrorCode[]::new));
        assertThat(httpStatus(e).is4xxClientError()).isTrue();
    }

    private static HttpStatus httpStatus(RuntimeException e) {
        ApiErrorType.Handler handler = new ApiErrorType.Handler();
        return HttpStatus.valueOf((e instanceof ApiException api
                ? handler.handle(api) : handler.handleConflict(e)).getStatusCode().value());
    }

    private static ApiException catchApi(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected an ApiException");
    }

    private void assertRevisions(String id, Integer... numbers) {
        String owner = store.load(id).createdBy();
        assertThat(service.getRevisions(id, owner))
                .extracting(RevisionView::revisionNumber)
                .containsExactly(numbers);
    }

    private String create(WorkflowSigningPolicy policy, String... users) throws Exception {
        List<CreateWorkflowRequest.SignerSpec> specs = new ArrayList<>();
        for (int i = 0; i < users.length; i++) {
            specs.add(new CreateWorkflowRequest.SignerSpec(i + 1, users[i], fp(users[i])));
        }
        WorkflowView view = service.createWorkflow(new CreateWorkflowRequest(
                "contract.pdf", policy.name(), specs), originalPdf(), users[0]);
        return view.workflowId();
    }

    private String createOwnedBy(String owner, String... users) throws Exception {
        List<CreateWorkflowRequest.SignerSpec> specs = new ArrayList<>();
        for (int i = 0; i < users.length; i++) {
            specs.add(new CreateWorkflowRequest.SignerSpec(i + 1, users[i], fp(users[i])));
        }
        return service.createWorkflow(new CreateWorkflowRequest("contract.pdf",
                "SEQUENTIAL", specs), originalPdf(), owner).workflowId();
    }

    /** Download the current revision as {@code user} and sign slot {@code order}. */
    private byte[] signLocally(String id, String user, int order) throws Exception {
        Path dir = Files.createTempDirectory("concurrency-sign");
        Path downloaded = dir.resolve("download.pdf");
        Files.write(downloaded, service.getDocument(id, user));
        connectToken();
        Path signed = dir.resolve("signed.pdf");
        workflowSigningService.signNextStep(downloaded, null, signed, order);
        return Files.readAllBytes(signed);
    }

    /** A second, independent signature of revision 0 (stale branch). */
    private byte[] signRevisionZeroAgain(String id) throws Exception {
        return signStoredRevision(id, 0, 1);
    }

    /** Signs stored revision {@code revision} for slot {@code order}. */
    private byte[] signStoredRevision(String id, int revision, int order) throws Exception {
        Path dir = Files.createTempDirectory("concurrency-rev");
        Path downloaded = dir.resolve("rev" + revision + ".pdf");
        Files.write(downloaded, store.readRevision(id, revision));
        connectToken();
        Path signed = dir.resolve("signed.pdf");
        workflowSigningService.signNextStep(downloaded, null, signed, order);
        return Files.readAllBytes(signed);
    }

    private void connectToken() {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());
    }

    private static Map<String, String> fingerprintCache;

    private String fp(String user) {
        if (fingerprintCache == null) {
            connectToken();
            fingerprintCache = tokenService.getCertificates().stream()
                    .collect(Collectors.toMap(c -> c.getAlias(), c -> c.getSha256Fingerprint()));
            tokenService.logout();
            tokenService.disconnect();
            simulator.removeToken();
        }
        return switch (user) {
            case "alice" -> fingerprintCache.get(MockSigningToken.DEFAULT_ALIAS);
            case "bob" -> fingerprintCache.get(MockSigningToken.THIRD_ALIAS);
            case "charlie" -> fingerprintCache.get(MockSigningToken.EC_ALIAS);
            default -> throw new IllegalArgumentException(user);
        };
    }

    private static byte[] originalPdf() throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** One-signer aggregate in {@code status}, signed or not (rev 0 [+1]). */
    private static SigningWorkflowAggregate aggregate(WorkflowState status, boolean signed) {
        SigningWorkflowAggregate base = SigningWorkflowAggregate.create("WF-GUARD",
                "doc.pdf", WorkflowSigningPolicy.SEQUENTIAL,
                List.of(WorkflowSignerRecord.bound(1, "alice", "AB".repeat(32))), "alice");
        base.transition(WorkflowState.READY_FOR_SIGNING);
        base.transition(WorkflowState.WAITING_FOR_SIGNER);
        if (signed) {
            base.markSignedByUser("alice", "op-guard");
        }
        SigningWorkflowAggregate.Snapshot s = base.snapshot();
        return SigningWorkflowAggregate.rehydrate(s.workflowId(), s.documentName(),
                s.policy(), s.signers(), s.revisions(), s.audit(), status,
                s.blockedReason(), s.claimedTurnUserId(), s.claimedTurnAt(), s.createdBy());
    }

    /**
     * Store wrapper simulating a database failure AFTER the revision file of
     * an accepted submission has been written.
     */
    static final class FailingStore implements WorkflowStore {
        private final WorkflowStore delegate;
        volatile boolean failSaveAfterRevisionWrite;
        volatile boolean revisionWritten;
        volatile int revisionWrites;

        FailingStore(WorkflowStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean exists(String workflowId) {
            return delegate.exists(workflowId);
        }

        @Override
        public SigningWorkflowAggregate load(String workflowId) {
            return delegate.load(workflowId);
        }

        @Override
        public void save(SigningWorkflowAggregate aggregate) {
            if (failSaveAfterRevisionWrite && revisionWritten) {
                revisionWritten = false;
                throw new IllegalStateException("simulated database failure");
            }
            delegate.save(aggregate);
        }

        @Override
        public void discardUncommittedRevision(String workflowId, int number) {
            delegate.discardUncommittedRevision(workflowId, number);
        }

        @Override
        public Object lockFor(String workflowId) {
            return delegate.lockFor(workflowId);
        }

        @Override
        public List<String> listWorkflowIds() {
            return delegate.listWorkflowIds();
        }

        @Override
        public void stageOriginal(String workflowId, byte[] bytes) {
            delegate.stageOriginal(workflowId, bytes);
        }

        @Override
        public byte[] readStagedOriginal(String workflowId) {
            return delegate.readStagedOriginal(workflowId);
        }

        @Override
        public void writeRevision(String workflowId, int number, byte[] bytes) {
            delegate.writeRevision(workflowId, number, bytes);
            revisionWritten = true;
            revisionWrites++;
        }

        @Override
        public byte[] readRevision(String workflowId, int number) {
            return delegate.readRevision(workflowId, number);
        }

        @Override
        public boolean existsRevision(String workflowId, int number) {
            return delegate.existsRevision(workflowId, number);
        }
    }
}

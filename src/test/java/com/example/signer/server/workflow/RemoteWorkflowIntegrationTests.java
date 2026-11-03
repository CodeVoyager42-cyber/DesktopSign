package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * End-to-end simulation of one signing session WITHOUT HTTP: the document is
 * loaded from disk, signed locally and submitted, the same contract the real
 * client satisfies, coordinated through the {@link RemoteWorkflowService}.
 *
 * <p>A document has exactly one signer — the account that uploaded it — so
 * what is checked here is that one signature's lifecycle, the refusals
 * around it, and what the task list says about it.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RemoteWorkflowIntegrationTests {

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

    @AfterEach
    void cleanUp() {
        tokenService.disconnect();
        simulator.removeToken();
    }

    @Test
    void aliceUploadsSignsAndTheDocumentIsFinished() throws Exception {
        Path dir = Files.createTempDirectory("remote-e2e");
        byte[] original = originalPdf(dir);

        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", fp("alice")), original, "alice");
        String workflowId = created.workflowId();
        assertThat(created.status()).isEqualTo("WAITING_FOR_SIGNER");
        assertThat(created.totalSignatures()).isEqualTo(1);

        TaskView task = service.getTask(workflowId, "alice");
        assertThat(task.signerOrder()).isEqualTo(1);
        assertThat(task.documentRevision()).isEqualTo(0);
        assertThat(task.signers()).hasSize(1);

        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(workflowId, 0, dir, rev1);

        SignatureSubmissionResponse r1 = service.submitSignature(workflowId, "alice",
                Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, null, "op-alice"));
        assertThat(r1.acceptedRevision()).isEqualTo(1);
        assertThat(r1.completed()).isTrue();
        assertThat(service.getWorkflow(workflowId, "alice").status()).isEqualTo("COMPLETED");

        // Revisions are immutable and contiguous on the server.
        assertThat(service.getRevisions(workflowId, "alice"))
                .hasSize(2)
                .extracting(com.example.signer.server.api.ApiContracts.RevisionView
                        ::revisionNumber)
                .containsExactly(0, 1);

        // The audit trail reconstructs the whole workflow.
        assertThat(service.getAudit(workflowId, "alice").stream()
                .map(com.example.signer.server.api.ApiContracts.AuditView::eventType))
                .contains("WORKFLOW_CREATED", "SIGNATURE_VALIDATED", "WORKFLOW_COMPLETED");

        // And it is out of the actionable list.
        assertThat(service.myTasks("alice")).noneMatch(w -> w.workflowId().equals(workflowId));
    }

    @Test
    void staleRevisionSubmissionIsRejectedAndWorkflowUnchanged() throws Exception {
        Path dir = Files.createTempDirectory("remote-stale");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", fp("alice")),
                originalPdf(dir), "alice");
        String workflowId = created.workflowId();

        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(workflowId, 0, dir, rev1);

        // The submission claims parent revision 0 but carries a hash that
        // does not match the server's revision 0 — a downloaded-then-branched
        // or tampered document. Must be rejected as stale.
        String wrongHash = "0".repeat(64);
        assertThatThrownBy(() -> service.submitSignature(workflowId, "alice",
                Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, wrongHash, "op-stale")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.STALE_REVISION);

        // Workflow state unchanged: still waiting for its signature on rev 0.
        assertThat(service.getWorkflow(workflowId, "alice").status())
                .isEqualTo("WAITING_FOR_SIGNER");
        assertThat(service.getWorkflow(workflowId, "alice").currentRevision()).isZero();

        // The honest submission (correct hash) still succeeds afterwards.
        String correctHash = store.load(workflowId).currentRevision().sha256();
        SignatureSubmissionResponse ok = service.submitSignature(workflowId, "alice",
                Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, correctHash, "op-honest"));
        assertThat(ok.completed()).isTrue();
    }

    @Test
    void duplicateSubmissionIsIdempotent() throws Exception {
        Path dir = Files.createTempDirectory("remote-idem");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", fp("alice")),
                originalPdf(dir), "alice");
        String workflowId = created.workflowId();

        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(workflowId, 0, dir, rev1);
        byte[] signed = Files.readAllBytes(rev1);

        SignatureSubmissionResponse first = service.submitSignature(workflowId,
                "alice", signed, new SignatureSubmissionRequest(0, null, "op-X"));
        SignatureSubmissionResponse retry = service.submitSignature(workflowId,
                "alice", signed, new SignatureSubmissionRequest(0, null, "op-X"));

        assertThat(retry.acceptedRevision()).isEqualTo(first.acceptedRevision());
        // No second transition happened: one revision was added, not two.
        assertThat(service.getWorkflow(workflowId, "alice").currentRevision()).isEqualTo(1);
        assertThat(service.getRevisions(workflowId, "alice")).hasSize(2);
    }

    @Test
    void nonParticipantCannotAccessTheWorkflow() throws Exception {
        Path dir = Files.createTempDirectory("remote-authz");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", fp("alice")),
                originalPdf(dir), "alice");
        // Security Phase 3: an outsider gets exactly the answer for a
        // workflow that does not exist, so ids cannot be probed.
        ApiException outsider = catchThrowableOfType(ApiException.class,
                () -> service.getTask(created.workflowId(), "mallory"));
        ApiException missing = catchThrowableOfType(ApiException.class,
                () -> service.getTask("WF-999999", "mallory"));
        assertThat(outsider.code()).isEqualTo(ApiErrorCode.WORKFLOW_NOT_FOUND);
        assertThat(outsider.code()).isEqualTo(missing.code());
        assertThat(outsider.getMessage()).isEqualTo(missing.getMessage());
        assertThatThrownBy(() -> service.getDocument(created.workflowId(), "mallory"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void nobodyButTheSignerCanSubmitASignature() throws Exception {
        Path dir = Files.createTempDirectory("remote-turn");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", fp("alice")),
                originalPdf(dir), "alice");
        String workflowId = created.workflowId();

        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(workflowId, 0, dir, rev1);

        // Another account — holding the signed bytes — is still a stranger to
        // this document, and is told what a stranger is told.
        assertThatThrownBy(() -> service.submitSignature(workflowId, "bob",
                Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, null, "op-bob")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.WORKFLOW_NOT_FOUND);
        assertThat(store.load(workflowId).signer().signed()).isFalse();
    }

    /**
     * The task list carries the signer's own state, and a cancelled request
     * stays visible (as CANCELLED) instead of silently disappearing.
     */
    @Test
    void theTaskListCarriesTheSignersOwnStateAndKeepsCancelledDocuments()
            throws Exception {
        Path dir = Files.createTempDirectory("tasks-order");
        String workflowId = service.createWorkflow(
                new CreateWorkflowRequest("order.pdf", fp("alice")),
                originalPdf(dir), "alice").workflowId();

        var mine = participating(workflowId);
        assertThat(mine.senderId()).isEqualTo("alice");
        assertThat(mine.myState().myStateLabel()).isEqualTo("YOUR_TURN");
        assertThat(mine.myState().waitingForMe()).isTrue();
        assertThat(mine.myState().participantOrder()).isEqualTo(1);
        assertThat(mine.updatedAt()).isNotNull();

        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(workflowId, 0, dir, rev1);
        service.submitSignature(workflowId, "alice", Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, null, "op-order-alice"));

        var signed = participating(workflowId);
        assertThat(signed.myState().myStateLabel()).isEqualTo("SIGNED");
        assertThat(signed.myState().signed()).isTrue();
        assertThat(signed.myState().waitingForMe()).isFalse();
        assertThat(signed.completedAt()).isNotNull();
    }

    @Test
    void aCancelledDocumentStaysInTheTaskList() throws Exception {
        Path dir = Files.createTempDirectory("tasks-cancelled");
        String workflowId = service.createWorkflow(
                new CreateWorkflowRequest("cancelled.pdf", fp("alice")),
                originalPdf(dir), "alice").workflowId();

        service.cancelWorkflow(workflowId, "alice");

        var cancelled = participating(workflowId);
        assertThat(cancelled.myState().myStateLabel()).isEqualTo("CANCELLED");
        assertThat(cancelled.myState().waitingForMe()).isFalse();
        assertThat(cancelled.myState().signed()).isFalse();
    }

    /* ------------------------------------------------------------------
     * helpers
     * ------------------------------------------------------------------ */

    /** alice's item for {@code workflowId} in the participating list. */
    private com.example.signer.server.api.ApiContracts.TaskItemView participating(
            String workflowId) {
        return service.listParticipating("alice").tasks().stream()
                .filter(t -> t.workflowId().equals(workflowId))
                .findFirst().orElseThrow();
    }

    /** Simulates one desktop signing session: download → sign locally. */
    private void signLocally(String workflowId, int expectedParent,
                             Path dir, Path output) throws Exception {
        TaskView task = service.getTask(workflowId, currentUserId(workflowId));
        assertThat(task.documentRevision()).isEqualTo(expectedParent);

        byte[] pdf = service.getDocument(workflowId, currentUserId(workflowId));
        Path downloaded = dir.resolve("download-" + expectedParent + ".pdf");
        Files.write(downloaded, pdf);

        connectToken();
        workflowSigningService.signNextStep(downloaded, null, output);
    }

    private String currentUserId(String workflowId) {
        return store.load(workflowId).currentSigner().userId();
    }

    private void connectToken() {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());
    }

    private byte[] originalPdf(Path dir) throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** The mock token certificate fingerprints, resolved once. */
    private static Map<String, String> fingerprintCache;

    private String fp(String user) {
        if (fingerprintCache == null) {
            connectToken();
            fingerprintCache = tokenService.getCertificates().stream()
                    .collect(Collectors.toMap(
                            c -> c.getAlias(), c -> c.getSha256Fingerprint()));
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
}

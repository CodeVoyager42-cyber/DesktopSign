package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.config.LoginProperties;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end multi-"machine" simulation (§33, §38) WITHOUT HTTP: three
 * signers in three isolated signing sessions (fresh disk load each time —
 * the same contract the real client satisfies), coordinated through the
 * {@link RemoteWorkflowService}.
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
    void aliceBobCharlieSignAcrossThreeIsolatedSessions() throws Exception {
        Path dir = Files.createTempDirectory("remote-e2e");
        byte[] original = originalPdf(dir);

        // ── Computer A: Alice creates the workflow and signs ─────────────
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp("alice")),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", fp("bob")),
                        new CreateWorkflowRequest.SignerSpec(3, "charlie", fp("charlie")))),
                original, "alice");
        String workflowId = created.workflowId();
        assertThat(created.status()).isEqualTo("WAITING_FOR_SIGNER");

        TaskView taskA = service.getTask(workflowId, "alice");
        assertThat(taskA.signerOrder()).isEqualTo(1);
        assertThat(taskA.documentRevision()).isEqualTo(0);

        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(workflowId, 0, dir, rev1);

        SignatureSubmissionResponse r1 = service.submitSignature(workflowId, "alice",
                Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, null, "op-alice"));
        assertThat(r1.acceptedRevision()).isEqualTo(1);
        assertThat(r1.nextSignerUserId()).isEqualTo("bob");

        // ── Computer B: Bob downloads revision 1 and signs ───────────────
        assertThat(service.myTasks("bob").stream()
                .anyMatch(w -> w.workflowId().equals(workflowId))).isTrue();

        Path rev2 = dir.resolve("rev2.pdf");
        signLocally(workflowId, 1, dir, rev2);
        SignatureSubmissionResponse r2 = service.submitSignature(workflowId, "bob",
                Files.readAllBytes(rev2),
                new SignatureSubmissionRequest(1, null, "op-bob"));
        assertThat(r2.nextSignerUserId()).isEqualTo("charlie");

        // ── Computer C: Charlie signs; workflow completes ────────────────
        Path rev3 = dir.resolve("rev3.pdf");
        signLocally(workflowId, 2, dir, rev3);
        SignatureSubmissionResponse r3 = service.submitSignature(workflowId, "charlie",
                Files.readAllBytes(rev3),
                new SignatureSubmissionRequest(2, null, "op-charlie"));
        assertThat(r3.completed()).isTrue();
        assertThat(service.getWorkflow(workflowId, "alice").status())
                .isEqualTo("COMPLETED");

        // Revisions are immutable and contiguous on the server.
        assertThat(service.getRevisions(workflowId, "alice"))
                .hasSize(4)
                .extracting(com.example.signer.server.api.ApiContracts.RevisionView::revisionNumber)
                .containsExactly(0, 1, 2, 3);

        // Audit trail reconstructs the whole workflow.
        assertThat(service.getAudit(workflowId, "alice").stream()
                .map(com.example.signer.server.api.ApiContracts.AuditView::eventType))
                .contains("WORKFLOW_CREATED", "SIGNATURE_VALIDATED",
                        "WORKFLOW_COMPLETED");
    }

    @Test
    void staleRevisionSubmissionIsRejectedAndWorkflowUnchanged() throws Exception {
        Path dir = Files.createTempDirectory("remote-stale");
        byte[] original = originalPdf(dir);

        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp("alice")),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", fp("bob")))),
                original, "alice");
        String workflowId = created.workflowId();

        // Alice signs revision 0 → server current becomes revision 1.
        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(workflowId, 0, dir, rev1);
        service.submitSignature(workflowId, "alice", Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, null, "op-alice"));

        // Bob's submission claims parent revision 1 but carries a hash that
        // does not match the server's revision 1 — a downloaded-then-branched
        // or tampered document. Must be rejected as stale.
        Path rev2 = dir.resolve("rev2.pdf");
        signLocally(workflowId, 1, dir, rev2);
        String wrongHash = "0".repeat(64);
        assertThatThrownBy(() -> service.submitSignature(workflowId, "bob",
                Files.readAllBytes(rev2),
                new SignatureSubmissionRequest(1, wrongHash, "op-bob")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((com.example.signer.server.api.ApiErrorType.ApiException) e)
                        .code())
                .isEqualTo(com.example.signer.server.api.ApiErrorType.ApiErrorCode.STALE_REVISION);

        // Workflow state unchanged: still waiting for Bob on revision 1.
        assertThat(service.getWorkflow(workflowId, "bob").status())
                .isEqualTo("WAITING_FOR_SIGNER");
        assertThat(service.getWorkflow(workflowId, "bob").currentRevision())
                .isEqualTo(1);

        // The honest submission (correct hash) still succeeds afterwards.
        String correctHash = store.load(workflowId).currentRevision().sha256();
        SignatureSubmissionResponse ok = service.submitSignature(workflowId, "bob",
                Files.readAllBytes(rev2),
                new SignatureSubmissionRequest(1, correctHash, "op-bob-2"));
        assertThat(ok.completed()).isTrue();
    }

    @Test
    void duplicateSubmissionIsIdempotent() throws Exception {
        Path dir = Files.createTempDirectory("remote-idem");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp("alice")),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", fp("bob")))),
                originalPdf(dir), "alice");
        String workflowId = created.workflowId();

        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(workflowId, 0, dir, rev1);
        byte[] signed = Files.readAllBytes(rev1);

        SignatureSubmissionResponse first = service.submitSignature(workflowId,
                "alice", signed, new SignatureSubmissionRequest(0, null, "op-X"));
        SignatureSubmissionResponse retry = service.submitSignature(workflowId,
                "alice", signed, new SignatureSubmissionRequest(0, null, "op-X"));

        assertThat(retry.acceptedRevision())
                .isEqualTo(first.acceptedRevision());
        // No second transition happened: still waiting for bob on revision 1.
        assertThat(service.getWorkflow(workflowId, "bob").currentRevision())
                .isEqualTo(1);
        assertThat(service.getWorkflow(workflowId, "bob").status())
                .isEqualTo("WAITING_FOR_SIGNER");
    }

    @Test
    void nonParticipantCannotAccessTheWorkflow() throws Exception {
        Path dir = Files.createTempDirectory("remote-authz");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp("alice")))),
                originalPdf(dir), "alice");
        assertThatThrownBy(() -> service.getTask(created.workflowId(), "mallory"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(com.example.signer.server.api.ApiErrorType.ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE);
        assertThatThrownBy(() -> service.getDocument(created.workflowId(), "mallory"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void wrongSignerCannotSubmitOutOfTurn() throws Exception {
        Path dir = Files.createTempDirectory("remote-turn");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp("alice")),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", fp("bob")))),
                originalPdf(dir), "alice");
        String workflowId = created.workflowId();

        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(workflowId, 0, dir, rev1);

        // Bob (not the current signer) tries to submit before Alice.
        assertThatThrownBy(() -> service.submitSignature(workflowId, "bob",
                Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, null, "op-bob-early")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(com.example.signer.server.api.ApiErrorType.ApiErrorCode.NOT_YOUR_TURN);
    }

    /* ------------------------------------------------------------------
     * helpers
     * ------------------------------------------------------------------ */

    /** Simulates one desktop signing session: download → sign locally. */
    /**
     * The Signing Tasks list carries the REAL signing order, the current
     * signer and the policy for each participant — and a cancelled request
     * stays visible (as CANCELLED) instead of silently disappearing.
     */
    @Test
    void signingTaskListCarriesTheRealSigningOrderAndCurrentSigner() throws Exception {
        Path dir = Files.createTempDirectory("tasks-order");
        String workflowId = service.createWorkflow(
                new CreateWorkflowRequest("order.pdf", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp("alice")),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", fp("bob")))),
                originalPdf(dir), "alice").workflowId();

        var bobsView = service.listParticipating("bob").tasks().stream()
                .filter(t -> t.workflowId().equals(workflowId)).findFirst().orElseThrow();
        assertThat(bobsView.signingPolicy()).isEqualTo("SEQUENTIAL");
        assertThat(bobsView.participants()).extracting(
                        com.example.signer.server.api.ApiContracts.TaskParticipantView::order,
                        com.example.signer.server.api.ApiContracts.TaskParticipantView::you,
                        com.example.signer.server.api.ApiContracts.TaskParticipantView::state)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, false, "CURRENT"),
                        org.assertj.core.groups.Tuple.tuple(2, true, "WAITING"));
        assertThat(bobsView.myState().myStateLabel()).isEqualTo("WAITING_FOR_OTHERS");
        assertThat(bobsView.updatedAt()).isNotNull();

        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(workflowId, 0, dir, rev1);
        service.submitSignature(workflowId, "alice", Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, null, "op-order-alice"));
        bobsView = service.listParticipating("bob").tasks().stream()
                .filter(t -> t.workflowId().equals(workflowId)).findFirst().orElseThrow();
        assertThat(bobsView.participants()).extracting(
                        com.example.signer.server.api.ApiContracts.TaskParticipantView::state)
                .containsExactly("SIGNED", "CURRENT");
        assertThat(bobsView.myState().waitingForMe()).isTrue();

        service.cancelWorkflow(workflowId, "alice");
        var cancelled = service.listParticipating("bob").tasks().stream()
                .filter(t -> t.workflowId().equals(workflowId)).findFirst().orElseThrow();
        assertThat(cancelled.myState().myStateLabel()).isEqualTo("CANCELLED");
        assertThat(cancelled.myState().waitingForMe()).isFalse();
    }

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

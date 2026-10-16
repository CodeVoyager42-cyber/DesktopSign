package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.BindCertificateRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5–7 behaviour end to end (§11, §33, §14/§17, §38): FLEXIBLE signing
 * policy with server-authoritative turn claims (including a true concurrent
 * claim race), and the UNBOUND → CERTIFICATE_BOUND → prepared lifecycle where
 * each signer binds their own certificate on "their" machine.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FlexibleWorkflowEnrollmentTests {

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

    /* ── FLEXIBLE policy: claims and out-of-order signing ──────────────── */

    @Test
    void flexibleWorkflowSignsOutOfOrderWithExclusiveClaims() throws Exception {
        Path dir = Files.createTempDirectory("flex-e2e");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", "FLEXIBLE", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp("alice")),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", fp("bob")),
                        new CreateWorkflowRequest.SignerSpec(3, "charlie", fp("charlie")))),
                originalPdf(dir), "alice");
        String id = created.workflowId();
        assertThat(created.signingPolicy()).isEqualTo("FLEXIBLE");
        assertThat(created.status()).isEqualTo("WAITING_FOR_SIGNER");
        assertThat(created.waitingForClaim()).isTrue();

        // Unclaimed: nobody holds the turn, both pending signers are eligible.
        TaskView task = service.getTask(id, "bob");
        assertThat(task.waitingForClaim()).isTrue();
        assertThat(task.eligibleSignerUserIds()).containsExactlyInAnyOrder(
                "alice", "bob", "charlie");

        // Charlie — NOT signer 1 — claims and signs first: legal in FLEXIBLE.
        service.claimTurn(id, "charlie");
        assertThat(service.getTask(id, "charlie").waitingForClaim()).isFalse();

        // While charlie holds the turn, bob's claim conflicts.
        assertThatThrownBy(() -> service.claimTurn(id, "bob"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.TURN_ALREADY_CLAIMED);

        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(id, 0, "charlie", dir, rev1);
        SignatureSubmissionResponse r1 = service.submitSignature(id, "charlie",
                Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, null, "op-charlie"));
        assertThat(r1.acceptedRevision()).isEqualTo(1);
        assertThat(r1.nextSignerUserId()).isNull(); // flexible: turn reopens

        // Alice signs second (out of order), bob last.
        service.claimTurn(id, "alice");
        Path rev2 = dir.resolve("rev2.pdf");
        signLocally(id, 1, "alice", dir, rev2);
        service.submitSignature(id, "alice", Files.readAllBytes(rev2),
                new SignatureSubmissionRequest(1, null, "op-alice"));

        // Without a claim bob can neither download nor submit: the turn gate.
        assertThat(service.getTask(id, "bob").waitingForClaim()).isTrue();
        assertThatThrownBy(() -> service.getDocument(id, "bob"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.NOT_YOUR_TURN);

        // Claim, then sign and submit as the turn holder.
        service.claimTurn(id, "bob");
        Path rev3 = dir.resolve("rev3.pdf");
        signLocally(id, 2, "bob", dir, rev3);
        SignatureSubmissionResponse r3 = service.submitSignature(id, "bob",
                Files.readAllBytes(rev3),
                new SignatureSubmissionRequest(2, null, "op-bob"));
        assertThat(r3.completed()).isTrue();
        assertThat(service.getWorkflow(id, "alice").status()).isEqualTo("COMPLETED");

        // Revisions stay contiguous and immutable regardless of sign order.
        assertThat(service.getRevisions(id, "alice"))
                .extracting(com.example.signer.server.api.ApiContracts.RevisionView::revisionNumber)
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    void concurrentClaimsElectExactlyOneSigner() throws Exception {
        Path dir = Files.createTempDirectory("flex-race");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", "FLEXIBLE", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp("alice")),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", fp("bob")))),
                originalPdf(dir), "alice");
        String id = created.workflowId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> aliceClaim = () -> {
                service.claimTurn(id, "alice");
                return "alice";
            };
            Callable<String> bobClaim = () -> {
                service.claimTurn(id, "bob");
                return "bob";
            };
            Future<String> a = pool.submit(aliceClaim);
            Future<String> b = pool.submit(bobClaim);
            // null until a.get() assigns it; if alice's claim fails, the
            // catch resolves the winner as the other signer.
            String winner = null;
            try {
                winner = a.get(10, TimeUnit.SECONDS);
                b.get(10, TimeUnit.SECONDS);
                throw new AssertionError("both claims succeeded");
            } catch (java.util.concurrent.ExecutionException e) {
                // Exactly one loser — the winner is the other one.
                winner = "alice".equals(winner) ? "alice" : "bob";
            }
            String loser = "alice".equals(winner) ? "bob" : "alice";
            TaskView after = service.getTask(id, winner);
            assertThat(after.waitingForClaim()).isFalse();
            assertThat(after.signerOrder()).isNotNull();
            // The loser's claim attempt failed; the turn belongs to the winner.
            assertThatThrownBy(() -> service.claimTurn(id, loser))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ApiErrorCode.TURN_ALREADY_CLAIMED);
        } finally {
            pool.shutdownNow();
        }
    }

    /* ── certificate binding lifecycle (§14/§17) ───────────────────────── */

    @Test
    void unboundWorkflowStaysDraftUntilEverySignerBindsThenPrepares() throws Exception {
        Path dir = Files.createTempDirectory("enroll-e2e");
        // Alice (bound at creation) invites bob WITHOUT his fingerprint.
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", null, List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp("alice")),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", null))),
                originalPdf(dir), "alice");
        String id = created.workflowId();

        // DRAFT: no revision-0 document exists yet; bob owes a certificate.
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.awaitingYourCertificate()).isTrue();
        assertThat(store.existsRevision(id, 0)).isFalse();
        // The original is staged so preparation can happen at bind time.
        assertThat(store.readStagedOriginal(id)).isNotNull();

        // Bob sees the workflow in his tasks and binds from "his" machine.
        assertThat(service.myTasks("bob")).anyMatch(w -> w.workflowId().equals(id));
        WorkflowView bound = service.bindCertificate(id, "bob",
                new BindCertificateRequest(fp("bob")));
        assertThat(bound.status()).isEqualTo("WAITING_FOR_SIGNER");
        assertThat(store.existsRevision(id, 0)).isTrue();

        // The prepared revision 0 carries the manifest listing BOTH
        // identities. SEQUENTIAL policy: alice (order 1) signs first.
        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(id, 0, "alice", dir, rev1);
        SignatureSubmissionResponse r = service.submitSignature(id, "alice",
                Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, null, "op-alice"));
        assertThat(r.acceptedRevision()).isEqualTo(1);

        // Bob — freshly enrolled and bound — completes the workflow.
        Path rev2 = dir.resolve("rev2.pdf");
        signLocally(id, 1, "bob", dir, rev2);
        SignatureSubmissionResponse done = service.submitSignature(id, "bob",
                Files.readAllBytes(rev2),
                new SignatureSubmissionRequest(1, null, "op-bob"));
        assertThat(done.completed()).isTrue();

        // The audit trail records the whole enrollment lifecycle.
        assertThat(service.getAudit(id, "alice"))
                .extracting(com.example.signer.server.api.ApiContracts.AuditView::eventType)
                .contains("CERTIFICATE_BOUND", "WORKFLOW_PREPARED");
    }

    @Test
    void nobodyCanBindAnotherUsersCertificate() throws Exception {
        Path dir = Files.createTempDirectory("enroll-authz");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", null, List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", fp("alice")),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", null))),
                originalPdf(dir), "alice");
        String id = created.workflowId();

        // A non-participant gets the same rejection as any outsider.
        assertThatThrownBy(() -> service.bindCertificate(id, "mallory",
                new BindCertificateRequest("AB".repeat(32))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE);
        // Still DRAFT — nothing leaked.
        assertThat(service.getWorkflow(id, "alice").status()).isEqualTo("DRAFT");

        // A malformed fingerprint is rejected before any state change.
        assertThatThrownBy(() -> service.bindCertificate(id, "bob",
                new BindCertificateRequest("isicod")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);

        // Non-participants cannot claim the turn either.
        assertThatThrownBy(() -> service.claimTurn(id, "mallory"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE);
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    /** Simulates one desktop signing session: claim-check → download → sign. */
    private void signLocally(String workflowId, int expectedParent,
                             String userId, Path dir, Path output) throws Exception {
        TaskView task = service.getTask(workflowId, userId);
        assertThat(task.documentRevision()).isEqualTo(expectedParent);
        byte[] pdf = service.getDocument(workflowId, userId);
        Path downloaded = dir.resolve("download-" + expectedParent + ".pdf");
        Files.write(downloaded, pdf);
        connectToken();
        // Same as the production remote path: the claimed signer order from
        // the task disambiguates when one token carries several certificates.
        workflowSigningService.signNextStep(downloaded, null, output,
                task.signerOrder());
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

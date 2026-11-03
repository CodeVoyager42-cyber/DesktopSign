package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.BindCertificateRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The UNBOUND → CERTIFICATE_BOUND → prepared lifecycle, end to end: a
 * document whose signer has not registered a certificate yet stays DRAFT
 * with no revision 0, and preparing it happens at bind time — on the machine
 * the signer's token is plugged into.
 *
 * <p>One document, one signer: the account that uploaded it.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CertificateEnrollmentTests {

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
    void anUnboundWorkflowStaysDraftUntilItsSignerBindsThenPrepares() throws Exception {
        Path dir = Files.createTempDirectory("enroll-e2e");
        // Uploaded without a fingerprint: the certificate is registered later,
        // from the machine the token is plugged into.
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", null), originalPdf(dir), "alice");
        String id = created.workflowId();

        // DRAFT: no revision-0 document exists yet; alice owes a certificate.
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.awaitingYourCertificate()).isTrue();
        assertThat(store.existsRevision(id, 0)).isFalse();
        // The original is staged so preparation can happen at bind time.
        assertThat(store.readStagedOriginal(id)).isNotNull();

        // It shows up in her tasks, and she binds from "her" machine.
        assertThat(service.myTasks("alice")).anyMatch(w -> w.workflowId().equals(id));
        WorkflowView bound = service.bindCertificate(id, "alice",
                new BindCertificateRequest(fp()));
        assertThat(bound.status()).isEqualTo("WAITING_FOR_SIGNER");
        assertThat(bound.awaitingYourCertificate()).isFalse();
        assertThat(store.existsRevision(id, 0)).isTrue();

        // The prepared revision 0 carries the manifest naming that identity,
        // and the one signature completes the document.
        Path rev1 = dir.resolve("rev1.pdf");
        signLocally(id, 0, "alice", dir, rev1);
        SignatureSubmissionResponse done = service.submitSignature(id, "alice",
                Files.readAllBytes(rev1),
                new SignatureSubmissionRequest(0, null, "op-alice"));
        assertThat(done.acceptedRevision()).isEqualTo(1);
        assertThat(done.completed()).isTrue();

        // The audit trail records the whole enrollment lifecycle.
        assertThat(service.getAudit(id, "alice"))
                .extracting(com.example.signer.server.api.ApiContracts.AuditView::eventType)
                .contains("CERTIFICATE_BOUND", "WORKFLOW_PREPARED");
    }

    /**
     * There is nothing to download before the certificate is registered —
     * revision 0 does not exist yet. The answer must say which state the
     * caller is in ({@code CERTIFICATE_NOT_BOUND}, 409) instead of failing
     * on the missing file, which used to surface as a 500.
     */
    @Test
    void aDraftDocumentCannotBeDownloadedUntilItIsPrepared() throws Exception {
        Path dir = Files.createTempDirectory("enroll-download");
        String id = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", null), originalPdf(dir), "alice")
                .workflowId();

        assertThatThrownBy(() -> service.getDocument(id, "alice"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.CERTIFICATE_NOT_BOUND);

        // Once the certificate is registered, the prepared document arrives.
        service.bindCertificate(id, "alice", new BindCertificateRequest(fp()));
        assertThat(service.getDocument(id, "alice")).isNotEmpty();
    }

    @Test
    void nobodyCanBindACertificateForSomebodyElsesDocument() throws Exception {
        Path dir = Files.createTempDirectory("enroll-authz");
        WorkflowView created = service.createWorkflow(
                new CreateWorkflowRequest("contract.pdf", null), originalPdf(dir), "alice");
        String id = created.workflowId();

        // A non-participant gets the same answer as for a missing workflow
        // (security Phase 3: not found and not yours look identical).
        assertThatThrownBy(() -> service.bindCertificate(id, "mallory",
                new BindCertificateRequest("AB".repeat(32))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.WORKFLOW_NOT_FOUND);
        // Still DRAFT — nothing leaked, nothing changed.
        assertThat(service.getWorkflow(id, "alice").status()).isEqualTo("DRAFT");

        // A malformed fingerprint is rejected before any state change.
        assertThatThrownBy(() -> service.bindCertificate(id, "alice",
                new BindCertificateRequest("isicod")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.BAD_REQUEST);
        assertThat(service.getWorkflow(id, "alice").status()).isEqualTo("DRAFT");
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    /** Simulates one desktop signing session: task → download → sign. */
    private void signLocally(String workflowId, int expectedParent,
                             String userId, Path dir, Path output) throws Exception {
        TaskView task = service.getTask(workflowId, userId);
        assertThat(task.documentRevision()).isEqualTo(expectedParent);
        byte[] pdf = service.getDocument(workflowId, userId);
        Path downloaded = dir.resolve("download-" + expectedParent + ".pdf");
        Files.write(downloaded, pdf);
        connectToken();
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

    private static String fingerprint;

    /** The mock token's default certificate fingerprint. */
    private String fp() {
        if (fingerprint == null) {
            connectToken();
            fingerprint = tokenService.getCertificates().stream()
                    .filter(c -> MockSigningToken.DEFAULT_ALIAS.equals(c.getAlias()))
                    .map(c -> c.getSha256Fingerprint())
                    .findFirst().orElseThrow();
            tokenService.logout();
            tokenService.disconnect();
            simulator.removeToken();
        }
        return fingerprint;
    }
}

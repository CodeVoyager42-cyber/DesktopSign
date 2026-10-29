package com.example.signer.server.workflow;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.exception.SignerException;
import com.example.signer.client.WorkflowApiClient;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowResponse;
import com.example.signer.server.api.ApiContracts.RevisionView;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full A-to-Z check of the remote signing workflow against the LIVE
 * coordination server (default {@code http://localhost:8080}), over real
 * HTTP — the same path the desktop application takes:
 *
 * <pre>
 * JWT login → DRAFT workflow with an unbound signer → own-certificate
 * binding → server-side preparation (revision 0) → flexible turn claim →
 * document download → local PKCS#11 (mock) signing → upload → server-side
 * signature validation → second signer → completion → revision chain +
 * audit trail.
 * </pre>
 *
 * <p>Skipped (JUnit assumption) when the live server is not reachable, so
 * the regular test suite stays green without it.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RemoteWorkflowLiveE2ETests {

    private static final String BASE = "http://localhost:8080";

    @Autowired
    private WorkflowSigningService workflowSigningService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    private WorkflowApiClient alice;
    private WorkflowApiClient bob;

    @BeforeEach
    void requireLiveServer() {
        Assumptions.assumeTrue(serverReachable(),
                "Live coordination server on " + BASE + " is not running");
    }

    @AfterEach
    void cleanUp() {
        if (alice != null) {
            alice.close();
        }
        if (bob != null) {
            bob.close();
        }
        tokenService.disconnect();
        simulator.removeToken();
    }

    @Test
    void fullRemoteWorkflowFromLoginToCompletionOverLiveHttp() throws Exception {
        // ── A: JWT login for both users ─────────────────────────────────
        alice = WorkflowApiClient.login(BASE, "alice", "alice-pass".toCharArray());
        bob = WorkflowApiClient.login(BASE, "bob", "bob-pass".toCharArray());
        assertThat(alice.myTasks()).isNotNull();
        assertThat(bob.myTasks()).isNotNull();

        Map<String, String> fingerprints = liveFingerprints();
        String aliceFp = fingerprints.get(MockSigningToken.DEFAULT_ALIAS);
        String bobFp = fingerprints.get(MockSigningToken.THIRD_ALIAS);

        // ── B: FLEXIBLE workflow — alice bound, bob owes a certificate ──
        CreateWorkflowResponse createResponse = alice.createWorkflow(
                "live-e2e-smoke-" + System.currentTimeMillis() + ".pdf",
                "FLEXIBLE",
                List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", aliceFp),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", null)),
                minimalPdf());
        WorkflowView created = createResponse.workflow();
        String id = created.workflowId();
        assertThat(created.signingPolicy()).isEqualTo("FLEXIBLE");
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.awaitingYourCertificate()).isTrue();
        // alice and bob are existing accounts, not email invitees, so this
        // flow emails nobody — and the response must not invent links for
        // them. (The emailed-link contract is covered where invitations are
        // actually sent: PostgresWorkflowStoreLiveTests.)
        assertThat(createResponse.invitations()).isEmpty();

        // ── C: bob sees the DRAFT task and binds HIS certificate ────────
        assertThat(bob.myTasks()).anyMatch(w -> w.workflowId().equals(id));
        WorkflowView prepared = bob.bindMyCertificate(id, bobFp);
        assertThat(prepared.status()).isEqualTo("WAITING_FOR_SIGNER");

        // ── D: atomic flexible turn claim (bob must not steal it) ───────
        // Before a claim, the task names every pending signer as eligible.
        TaskView beforeClaim = alice.task(id);
        assertThat(beforeClaim.waitingForClaim()).isTrue();
        assertThat(beforeClaim.eligibleSignerUserIds())
                .containsExactlyInAnyOrder("alice", "bob");
        TaskView aliceTask = alice.claimTurn(id);
        assertThat(aliceTask.waitingForClaim()).isFalse();
        assertThat(aliceTask.signerOrder()).isEqualTo(1);
        assertThatThrownBy(() -> bob.claimTurn(id))
                .isInstanceOf(SignerException.class);

        // ── E: download + LOCAL PKCS#11 (mock) signing by alice ─────────
        assertThat(aliceTask.documentRevision()).isEqualTo(0);
        Path signed1 = signLocally(alice.document(id), aliceTask.signerOrder());

        // ── F: upload + server-side signature validation ────────────────
        SignatureSubmissionResponse r1 = alice.submitSignature(id,
                new SignatureSubmissionRequest(0, null,
                        "live-e2e-alice-" + UUID.randomUUID()),
                Files.readAllBytes(signed1));
        assertThat(r1.acceptedRevision()).isEqualTo(1);
        assertThat(r1.nextSignerUserId()).isNull(); // flexible: turn reopens

        // ── G: bob claims, downloads, signs and completes ───────────────
        TaskView bobTask = bob.claimTurn(id);
        assertThat(bobTask.waitingForClaim()).isFalse();
        assertThat(bobTask.signerOrder()).isEqualTo(2);
        assertThat(bobTask.documentRevision()).isEqualTo(1);
        Path signed2 = signLocally(bob.document(id), bobTask.signerOrder());
        SignatureSubmissionResponse r2 = bob.submitSignature(id,
                new SignatureSubmissionRequest(1, null,
                        "live-e2e-bob-" + UUID.randomUUID()),
                Files.readAllBytes(signed2));
        assertThat(r2.completed()).isTrue();

        // ── H: end state — revision chain, audit, task lists ────────────
        assertThat(alice.revisions(id))
                .extracting(RevisionView::revisionNumber)
                .containsExactly(0, 1, 2);
        assertThat(alice.audit(id))
                .extracting(a -> a.eventType())
                .contains("CERTIFICATE_BOUND", "WORKFLOW_PREPARED",
                        "SIGNING_TURN_CLAIMED", "SIGNATURE_VALIDATED");
        assertThat(alice.myTasks()).noneMatch(w -> w.workflowId().equals(id));
        assertThat(bob.myTasks()).noneMatch(w -> w.workflowId().equals(id));
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    /** Live fingerprints of the (now stable) mock token identities. */
    private Map<String, String> liveFingerprints() {
        connectToken();
        Map<String, String> result = tokenService.getCertificates().stream()
                .collect(Collectors.toMap(
                        SigningCertificate::getAlias,
                        SigningCertificate::getSha256Fingerprint));
        tokenService.disconnect();
        simulator.removeToken();
        return result;
    }

    /**
     * Simulates one desktop signing session: the downloaded bytes are written
     * to disk and signed through the same domain path the app uses, with the
     * claimed signer order disambiguating a multi-certificate token.
     */
    private Path signLocally(byte[] documentBytes,
                             Integer expectedSignerOrder) throws Exception {
        Path dir = Files.createTempDirectory("live-e2e");
        Path downloaded = dir.resolve("download.pdf");
        Files.write(downloaded, documentBytes);
        connectToken();
        Path signed = dir.resolve("signed.pdf");
        workflowSigningService.signNextStep(downloaded, null, signed,
                expectedSignerOrder);
        return signed;
    }

    private void connectToken() {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());
    }

    private byte[] minimalPdf() throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static boolean serverReachable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", 8080), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

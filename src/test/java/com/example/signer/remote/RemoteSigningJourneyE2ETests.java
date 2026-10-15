package com.example.signer.remote;

import com.example.signer.server.RemoteWorkflowService;
import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.verification.SignatureStatus;
import com.example.signer.verification.SignatureVerificationResult;
import com.example.signer.workflow.WorkflowSigningService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service calls behind the redesigned signing screens, over real HTTP
 * against an in-process coordination server: Document (prepareReview),
 * Signature (smart-card check against the certificate the workflow expects),
 * progress stages, and the success screen's status, which comes from
 * re-verifying the signed file — not from assuming success.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server")
@TestPropertySource(properties = "signature.remote-server.storage=${java.io.tmpdir}/signer-journey-e2e")
class RemoteSigningJourneyE2ETests {

    @LocalServerPort
    private int port;

    @Autowired
    private RemoteWorkflowService service;

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
    void reviewCardCheckProgressAndVerifiedResult() throws Exception {
        String aliceFp = fingerprint(MockSigningToken.DEFAULT_ALIAS);
        String bobFp = fingerprint(MockSigningToken.THIRD_ALIAS);
        String id = service.createWorkflow(new CreateWorkflowRequest("contract.pdf",
                "SEQUENTIAL", List.of(
                        new CreateWorkflowRequest.SignerSpec(1, "alice", aliceFp),
                        new CreateWorkflowRequest.SignerSpec(2, "bob", bobFp))),
                pdf(), "alice").workflowId();

        String base = "http://localhost:" + port;
        WorkflowApiClient client = new WorkflowApiClient(base,
                WorkflowApiClient.Session.login(base, "alice", "alice-pass"));
        try {
            RemoteSigningService signing = new RemoteSigningService(
                    client, workflowSigningService, tokenService);
            Path dir = Files.createTempDirectory("journey");

            // Step 1 — Document
            RemoteSigningService.ReviewPackage review = signing.prepareReview(id, dir);
            assertThat(review.task().signerOrder()).isEqualTo(1);
            assertThat(review.document().parseFailure()).isNull();
            assertThat(review.document().verification().signatures()).isEmpty();
            assertThat(review.requiredCertificate().matchesFingerprint(aliceFp)).isTrue();

            // Step 2 — Signature: no token yet, then the token is plugged in
            assertThat(SigningCardCheck.run(tokenService, review.requiredCertificate()).state())
                    .isEqualTo(SigningCardCheck.State.NOT_DETECTED);
            simulator.insertToken();
            SigningCardCheck card = SigningCardCheck.run(tokenService, review.requiredCertificate());
            assertThat(card.state()).isEqualTo(SigningCardCheck.State.READY);
            assertThat(card.certificate().getAlias()).isEqualTo(MockSigningToken.DEFAULT_ALIAS);

            // Step 3 — Confirm + progress
            List<RemoteSigningService.Stage> stages = new CopyOnWriteArrayList<>();
            AtomicReference<Path> signedFile = new AtomicReference<>();
            char[] pin = MockSigningToken.defaultPin();
            // The box the user drew in the workspace + the reason/location
            // picked in the panel, exactly as the dialog passes them.
            com.example.signer.signing.SignaturePlacement drawn =
                    new com.example.signer.signing.SignaturePlacement(0, 120f, 300f, 180f, 60f);
            SignatureSubmissionResponse response = signing.signRemoteStep(id, pin,
                    (file, order) -> drawn,
                    new WorkflowSigningService.SignatureAppearanceHint("Contract approval", "Rabat"),
                    review.task().documentRevision(), dir,
                    new RemoteSigningService.Listener() {
                        @Override
                        public void stage(RemoteSigningService.Stage stage) {
                            stages.add(stage);
                        }

                        @Override
                        public void signedFile(Path signedPdf) {
                            signedFile.set(signedPdf);
                        }
                    });
            assertThat(stages).containsExactly(RemoteSigningService.Stage.values());
            assertThat(new String(pin)).isEqualTo("\0".repeat(pin.length));
            assertThat(response.acceptedRevision()).isEqualTo(1);
            assertThat(response.nextSignerUserId()).isEqualTo("bob");

            // Success screen data: re-verified signed file
            SignatureVerificationResult verification =
                    workflowSigningService.load(signedFile.get()).verification();
            assertThat(verification.signatures()).hasSize(1);
            var mine = verification.signatures().get(0);
            assertThat(SignatureStatus.of(mine, verification)).isEqualTo(SignatureStatus.VALID);
            assertThat(mine.properties().certSubject()).contains("CN=Mock Signer");
            assertThat(mine.signerCertFingerprint().replace(":", ""))
                    .isEqualToIgnoringCase(aliceFp.replace(":", ""));
            assertThat(mine.properties().reason()).isEqualTo("Contract approval");
            assertThat(mine.properties().location()).isEqualTo("Rabat");

            // The visible box is where the user put it: the widget rectangle
            // in the PDF and the placement recorded in the manifest.
            var signedDoc = workflowSigningService.load(signedFile.get());
            var step1 = signedDoc.manifest().stepsInOrder().get(0);
            assertThat(step1.placement()).isEqualTo(drawn);
            try (var pd = org.apache.pdfbox.Loader.loadPDF(signedFile.get().toFile())) {
                var widget = pd.getSignatureFields().get(0).getWidgets().get(0);
                var rect = widget.getRectangle();
                assertThat(rect.getLowerLeftX()).isCloseTo(120f, org.assertj.core.data.Offset.offset(0.5f));
                assertThat(rect.getLowerLeftY()).isCloseTo(300f, org.assertj.core.data.Offset.offset(0.5f));
                assertThat(rect.getWidth()).isCloseTo(180f, org.assertj.core.data.Offset.offset(0.5f));
                assertThat(rect.getHeight()).isCloseTo(60f, org.assertj.core.data.Offset.offset(0.5f));
                assertThat(pd.getPages().indexOf(widget.getPage())).isZero();
            }

            // The next signer's workspace shows alice's box as an existing
            // signature (so bob can avoid covering it).
            WorkflowApiClient bob = new WorkflowApiClient(base,
                    WorkflowApiClient.Session.login(base, "bob", "bob-pass"));
            try {
                var bobReview = new RemoteSigningService(bob, workflowSigningService, tokenService)
                        .prepareReview(id, Files.createTempDirectory("journey-bob"));
                assertThat(bobReview.task().signerOrder()).isEqualTo(2);
                assertThat(bobReview.otherPlacements()).containsExactly(drawn);
                assertThat(bobReview.requiredCertificate().matchesFingerprint(bobFp)).isTrue();
            } finally {
                bob.close();
            }
        } finally {
            client.close();
        }
    }

    @Test
    void aDocumentThatChangedAfterReviewIsRefusedBeforeTheTokenIsUsed() throws Exception {
        String aliceFp = fingerprint(MockSigningToken.DEFAULT_ALIAS);
        String id = service.createWorkflow(new CreateWorkflowRequest("nda.pdf",
                "SEQUENTIAL", List.of(new CreateWorkflowRequest.SignerSpec(1, "alice", aliceFp))),
                pdf(), "alice").workflowId();
        String base = "http://localhost:" + port;
        WorkflowApiClient client = new WorkflowApiClient(base,
                WorkflowApiClient.Session.login(base, "alice", "alice-pass"));
        try {
            simulator.insertToken();
            List<RemoteSigningService.Stage> stages = new CopyOnWriteArrayList<>();
            char[] pin = MockSigningToken.defaultPin();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RemoteSigningService(
                            client, workflowSigningService, tokenService)
                    .signRemoteStep(id, pin, (f, o) -> null, null,
                            /* reviewed an older revision */ 7,
                            Files.createTempDirectory("stale"),
                            new RemoteSigningService.Listener() {
                                @Override
                                public void stage(RemoteSigningService.Stage stage) {
                                    stages.add(stage);
                                }
                            }))
                    .isInstanceOfSatisfying(com.example.signer.exception.SignerException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(
                                    com.example.signer.exception.ErrorCode.WORKFLOW_STALE_DOCUMENT));
            assertThat(stages).containsExactly(RemoteSigningService.Stage.DOWNLOAD);
            assertThat(new String(pin)).isEqualTo("\0".repeat(pin.length));
            assertThat(tokenService.isAuthenticated()).isFalse();
            assertThat(service.getTask(id, "alice").documentRevision()).isZero();
        } finally {
            client.close();
        }
    }

    private String fingerprint(String alias) {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        String fp = tokenService.getCertificates().stream()
                .filter(c -> alias.equals(c.getAlias()))
                .map(SigningCertificate::getSha256Fingerprint)
                .findFirst().orElseThrow();
        tokenService.disconnect();
        simulator.removeToken();
        return fp;
    }

    private static byte[] pdf() throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}

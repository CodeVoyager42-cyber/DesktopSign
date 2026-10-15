package com.example.signer.workflow;

import com.example.signer.signing.SignaturePlacement;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.verification.SignatureVerificationResult;
import com.example.signer.workflow.WorkflowSigningEngine.ChainVerificationException;
import com.example.signer.workflow.WorkflowSigningEngine.SignerRevision;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the sequential multi-signer workflow (§15/§16):
 * three signers, one shared mock token representing three swapped physical
 * devices, every signer producing one revision that must keep all earlier
 * signatures valid.
 */
@SpringBootTest
class WorkflowSigningEngineIntegrationTests {

    @Autowired
    private WorkflowSigningEngine engine;

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
    void threeSignersProduceThreeValidRevisionsWithoutInvalidatingEarlierOnes()
            throws Exception {
        // ── the document ──
        Path dir = Files.createTempDirectory("multi-sign-test");
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);

        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.THREE_SIGNERS);
        engine.start(workflow);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.READY);

        List<SignerRevision> revisions = new ArrayList<>();
        Path input = original;
        for (int order = 1; order <= 3; order++) {
            // ── per-signer token lifecycle (device swap between signers) ──
            engine.prepareCurrentSigner(workflow);
            assertThat(tokenService.getState()).isEqualTo(TokenState.NO_TOKEN);

            simulator.insertToken(); // signer n plugs in their device
            tokenService.detect();
            assertThat(tokenService.getState()).isEqualTo(TokenState.CERTIFICATE_AVAILABLE);

            engine.assignCertificate(workflow, MockSigningToken.DEFAULT_ALIAS);
            engine.authenticate(workflow, MockSigningToken.defaultPin());
            assertThat(workflow.getSigner(order).getStatus()).isEqualTo(SigningStatus.SIGNING);

            // ── sign revision n with a signer-specific visible placement ──
            Path output = dir.resolve("revision-" + order + ".pdf");
            SignaturePlacement placement =
                    new SignaturePlacement(0, 24f * order, 24f, 170f, 55f);
            SignerRevision revision =
                    engine.sign(workflow, input, placement, output);
            revisions.add(revision);

            // ── §15: signer n must not invalidate signers 1..n-1 ──
            assertThat(revision.verification().valid()).isTrue();
            assertThat(revision.verification().signatures()).hasSize(order);
            for (SignatureVerificationResult.SignatureDetail detail
                    : revision.verification().signatures()) {
                assertThat(detail.valid())
                        .as("signature %s must stay valid after signer %s",
                                detail.index() + 1, order)
                        .isTrue();
            }
            input = output;
        }

        // ── the workflow completed ──
        assertThat(workflow.isComplete()).isTrue();
        assertThat(WorkflowSigningEngine.signedOrders(workflow))
                .containsExactly(1, 2, 3);
        assertThat(revisions).hasSize(3);
        assertThat(revisions.get(2).revisionPdf())
                .hasSameFileSystemAs(revisions.get(0).revisionPdf());
        assertThat(Files.size(revisions.get(2).revisionPdf()))
                .isGreaterThan(Files.size(original));

        // ── §16: the final document re-verifies as a whole ──
        Path finalCheck = Files.createTempFile("final-check", ".pdf");
        try (PDDocument doc = Loader.loadPDF(revisions.get(2).revisionPdf().toFile())) {
            assertThat(doc.getSignatureDictionaries()).hasSize(3);
        }
    }

    @Test
    void wrongPinFailsTheCurrentSignerWithoutAdvancingTheWorkflow() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.TWO_SIGNERS);
        engine.start(workflow);

        simulator.insertToken();
        tokenService.detect();
        engine.assignCertificate(workflow, MockSigningToken.DEFAULT_ALIAS);

        assertThatThrownBy(() -> engine.authenticate(workflow, "0000".toCharArray()))
                .isInstanceOf(RuntimeException.class);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.FAILED);
        assertThat(workflow.getSigner(2).getStatus()).isEqualTo(SigningStatus.PENDING);
        assertThat(workflow.isComplete()).isFalse();

        // Retry path (§9): reset the failed signer and succeed this time.
        workflow.resetSigner(1);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.READY);
        engine.authenticate(workflow, MockSigningToken.defaultPin());
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.SIGNING);
    }

    @Test
    void signWithoutCertificateAssignmentIsRejected() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.SINGLE);
        engine.start(workflow);
        assertThatThrownBy(() -> engine.authenticate(workflow, MockSigningToken.defaultPin()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void engineRequiresTheWorkflowToHaveStartedBeforeSigning() throws Exception {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.SINGLE);
        // No start(): signer 1 is PENDING, engine.sign must refuse.
        Path dir = Files.createTempDirectory("engine-guard-test");
        Path original = dir.resolve("doc.pdf");
        createOnePagePdf(original);
        Path output = dir.resolve("out.pdf");

        assertThatThrownBy(() -> engine.sign(workflow, original, null, output))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preparingTheNextSignerAfterWorkflowCompletionIsAHarmlessNoOp() throws Exception {
        // Regression: the dialog used to call engine.prepareCurrentSigner after
        // the LAST signer finished, which threw "Workflow is already complete",
        // killed the completion handler and froze the UI before "Save Signed
        // PDF…" could be enabled.
        Path dir = Files.createTempDirectory("engine-complete-test");
        Path original = dir.resolve("doc.pdf");
        createOnePagePdf(original);

        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.SINGLE);
        engine.start(workflow);
        engine.prepareCurrentSigner(workflow);
        simulator.insertToken();
        tokenService.detect();
        engine.assignCertificate(workflow, MockSigningToken.DEFAULT_ALIAS);
        engine.authenticate(workflow, MockSigningToken.defaultPin());

        Path output = dir.resolve("revision-1.pdf");
        engine.sign(workflow, original, null, output);
        assertThat(workflow.isComplete()).isTrue();

        // The dialog's post-sign call: must not throw once the workflow is done.
        assertThatCode(() -> engine.prepareCurrentSigner(workflow))
                .doesNotThrowAnyException();
        assertThat(workflow.isComplete()).isTrue();
        assertThat(WorkflowSigningEngine.signedOrders(workflow)).containsExactly(1);
    }

    @Test
    void oneSignerOnThisComputerProducesOneValidVisibleSignature() throws Exception {
        // "One signer" on the home screen runs in the same workspace as two or
        // three signers: the engine must give an ordinary signed PDF with
        // exactly one valid signature at the box the signer placed.
        Path dir = Files.createTempDirectory("engine-single-test");
        Path original = dir.resolve("doc.pdf");
        createOnePagePdf(original);

        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.SINGLE);
        engine.start(workflow);
        engine.prepareCurrentSigner(workflow);
        simulator.insertToken();
        tokenService.detect();
        engine.assignCertificate(workflow, MockSigningToken.DEFAULT_ALIAS);
        engine.authenticate(workflow, MockSigningToken.defaultPin());

        Path output = dir.resolve("signed.pdf");
        SignerRevision revision = engine.sign(workflow, original,
                new SignaturePlacement(0, 72f, 72f, 170f, 55f), output);

        assertThat(workflow.isComplete()).isTrue();
        assertThat(revision.verification().valid()).isTrue();
        assertThat(revision.verification().signatures()).hasSize(1);
        assertThat(revision.verification().signatures().get(0).valid()).isTrue();
        assertThat(Files.size(output)).isGreaterThan(Files.size(original));
    }

    /**
     * End-to-end identity check (turn handovers): three *named* signers sign
     * sequentially; after every handover the workflow's current signer must
     * carry the right name, order and status, and after every signature the
     * revision record must carry that signer's name plus the certificate
     * subject that actually created the signature. Proves names and
     * certificate subjects propagate through the whole chain.
     */
    @Test
    void threeNamedSignersCarryIdentityThroughEveryHandover() throws Exception {
        String[] names = {"Ahmed Benali", "Sara Amrani", "Youssef Alaoui"};
        Path dir = Files.createTempDirectory("identity-flow-test");
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);

        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.THREE_SIGNERS);
        for (int i = 0; i < 3; i++) {
            workflow.getSigner(i + 1).setDisplayName(names[i]);
        }
        engine.start(workflow);

        List<SignerRevision> revisions = new ArrayList<>();
        Path input = original;
        for (int order = 1; order <= 3; order++) {
            // ── handover: the CURRENT signer must be the right person ──
            engine.prepareCurrentSigner(workflow);
            Signer current = workflow.getCurrentSigner();
            assertThat(current).isNotNull();
            assertThat(current.getOrder())
                    .as("turn must hand over to signer %s", order)
                    .isEqualTo(order);
            assertThat(current.getDisplayName())
                    .as("turn %s must belong to the right named signer", order)
                    .isEqualTo(names[order - 1]);
            assertThat(current.getStatus()).isEqualTo(SigningStatus.READY);

            // ── per-signer token lifecycle (device swap between signers) ──
            simulator.insertToken();
            tokenService.detect();
            engine.assignCertificate(workflow, MockSigningToken.DEFAULT_ALIAS);
            engine.authenticate(workflow, MockSigningToken.defaultPin());

            Path output = dir.resolve("revision-" + order + ".pdf");
            SignerRevision revision = engine.sign(workflow, input, null, output);
            revisions.add(revision);

            // ── the revision must carry the signer's identity end to end ──
            assertThat(revision.displayName())
                    .as("revision %s must record the signer's name", order)
                    .isEqualTo(names[order - 1]);
            assertThat(revision.signerSubject())
                    .as("revision %s must record the certificate subject", order)
                    .contains("Mock Signer");
            assertThat(revision.order()).isEqualTo(order);
            assertThat(workflow.getSigner(order).getStatus()).isEqualTo(SigningStatus.SIGNED);

            // ── the UI-facing chain must keep earlier signatures valid ──
            assertThat(revision.verification().valid()).isTrue();
            assertThat(revision.verification().signatures()).hasSize(order);

            input = output;
        }

        // ── after the last handover there is no next signer ──
        assertThat(workflow.isComplete()).isTrue();
        assertThat(workflow.getCurrentSigner()).isNull();
        assertThat(WorkflowSigningEngine.signedOrders(workflow)).containsExactly(1, 2, 3);
    }

    /**
     * The fallback identity rule: a signer without a custom name displays as
     * "Signer n" (what the panel, strip and audit show when the name field
     * is left empty).
     */
    @Test
    void signersWithoutCustomNamesFallBackToGeneratedNames() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.TWO_SIGNERS);
        assertThat(workflow.getSigner(1).getDisplayName()).isEqualTo("Signer 1");
        assertThat(workflow.getSigner(2).getDisplayName()).isEqualTo("Signer 2");
        assertThat(workflow.getSigner(1).hasCustomName()).isFalse();

        workflow.getSigner(2).setDisplayName("   Sara Amrani   ");
        assertThat(workflow.getSigner(2).getDisplayName()).isEqualTo("Sara Amrani");
        assertThat(workflow.getSigner(2).hasCustomName()).isTrue();
        assertThat(workflow.getSigner(2).getDisplayName())
                .isEqualTo("Sara Amrani");
    }

    private static void createOnePagePdf(Path target) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(target.toFile());
        }
    }
}

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
    void wrongPinFailsTheCurrentSignerWithoutAdvancingTheWorkflow() {
        SigningWorkflow workflow = SigningWorkflow.create();
        engine.start(workflow);

        simulator.insertToken();
        tokenService.detect();
        engine.assignCertificate(workflow, MockSigningToken.DEFAULT_ALIAS);

        assertThatThrownBy(() -> engine.authenticate(workflow, "0000".toCharArray()))
                .isInstanceOf(RuntimeException.class);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.FAILED);
        assertThat(workflow.isComplete()).isFalse();

        // Retry path (§9): reset the failed signer and succeed this time.
        workflow.resetSigner(1);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.READY);
        engine.authenticate(workflow, MockSigningToken.defaultPin());
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.SIGNING);
    }

    @Test
    void signWithoutCertificateAssignmentIsRejected() {
        SigningWorkflow workflow = SigningWorkflow.create();
        engine.start(workflow);
        assertThatThrownBy(() -> engine.authenticate(workflow, MockSigningToken.defaultPin()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void engineRequiresTheWorkflowToHaveStartedBeforeSigning() throws Exception {
        SigningWorkflow workflow = SigningWorkflow.create();
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

        SigningWorkflow workflow = SigningWorkflow.create();
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

        SigningWorkflow workflow = SigningWorkflow.create();
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
     * The fallback identity rule: a signer without a custom name displays as
     * "Signer n" (what the panel, strip and audit show when the name field
     * is left empty).
     */
    @Test
    void aSignerWithoutACustomNameFallsBackToTheGeneratedName() {
        SigningWorkflow workflow = SigningWorkflow.create();
        assertThat(workflow.getSigner(1).getDisplayName()).isEqualTo("Signer 1");
        assertThat(workflow.getSigner(1).hasCustomName()).isFalse();

        workflow.getSigner(1).setDisplayName("   Sara Amrani   ");
        assertThat(workflow.getSigner(1).getDisplayName()).isEqualTo("Sara Amrani");
        assertThat(workflow.getSigner(1).hasCustomName()).isTrue();
    }

    private static void createOnePagePdf(Path target) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(target.toFile());
        }
    }
}

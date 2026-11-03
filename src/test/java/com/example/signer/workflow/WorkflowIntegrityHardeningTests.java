package com.example.signer.workflow;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.signing.PdfSigningService;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.verification.SignatureVerificationResult;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integrity-hardening tests: document-hash binding, DocMDP fail-closed and
 * its detection, and placement bounds validation (prompt phases 2/3/6).
 */
@SpringBootTest
class WorkflowIntegrityHardeningTests {

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @Autowired
    private WorkflowSigningService workflowSigningService;

    @Autowired
    private PdfSigningService pdfSigningService;

    @Autowired
    private com.example.signer.verification.PdfVerificationService verificationService;

    @AfterEach
    void cleanUp() {
        tokenService.disconnect();
        simulator.removeToken();
    }

    /* ------------------------------------------------------------------
     * Document identity: workflows are bound to the original bytes
     * ------------------------------------------------------------------ */

    @Test
    void workflowIsBoundToTheOriginalDocumentHash() throws Exception {
        Path dir = Files.createTempDirectory("wf-bind");
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);

        WorkflowManifest manifest = workflowSigningService.createWorkflow(
                original, "contract.pdf", List.of(aliceStep()));
        assertThat(manifest.originalDocumentSha256())
                .isNotBlank()
                .hasSize(64)
                .matches("[0-9a-f]{64}");

        // Positive path: seeding into the SAME bytes works.
        Path workflowPdf = dir.resolve("contract-wf.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, workflowPdf);

        // Negative path: the same manifest must be refused for DIFFERENT bytes.
        Path other = dir.resolve("other.pdf");
        createOnePagePdf(other);
        Path substituted = dir.resolve("substituted.pdf");
        Files.write(substituted,
                concatenate(Files.readAllBytes(other),
                        "\n% a different document".getBytes(StandardCharsets.US_ASCII)));

        assertThatThrownBy(() -> workflowSigningService.writeManifestIntoCopy(
                substituted, manifest, dir.resolve("should-not-exist.pdf")))
                .isInstanceOf(SignerException.class)
                .extracting(e -> ((SignerException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORKFLOW_TAMPERED);
        assertThat(dir.resolve("should-not-exist.pdf")).doesNotExist();
    }

    /* ------------------------------------------------------------------
     * DocMDP: certification permissions are detected and fail closed
     * ------------------------------------------------------------------ */

    @Test
    void restrictiveDocMdpIsDetectedAndBlocksFurtherWorkflowSigning() throws Exception {
        Path dir = Files.createTempDirectory("wf-docmdp");
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);

        WorkflowManifest manifest = workflowSigningService.createWorkflow(
                original, "contract.pdf",
                List.of(aliceStep(), karimStep()));
        Path workflowPdf = dir.resolve("contract-wf.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, workflowPdf);

        // Sign ONCE directly through the signing service, with a customizer
        // that adds a restrictive catalog /Perms/DocMDP (P=1, no changes
        // permitted) inside the SAME signed revision.
        connectToken();
        Path certified = dir.resolve("certified.pdf");
        pdfSigningService.signPdf(workflowPdf, MockSigningToken.DEFAULT_ALIAS,
                SigningAlgorithm.RSA_SHA256, certified, null, null,
                doc -> {
                    COSDictionary mdp = new COSDictionary();
                    mdp.setInt(COSName.getPDFName("P"), 1);
                    COSDictionary perms = new COSDictionary();
                    perms.setItem(COSName.getPDFName("DocMDP"), mdp);
                    doc.getDocumentCatalog().getCOSObject()
                            .setItem(COSName.getPDFName("Perms"), perms);
                });

        // The certification permission is detected on the first signature.
        SignatureVerificationResult result = verificationService.verifyPdf(certified);
        assertThat(result.valid()).isTrue();
        assertThat(result.signatures()).hasSize(1);
        assertThat(result.signatures().get(0).docMdp()).isEqualTo(1);

        // Step 1's signer may not sign again, and step 2 may not continue:
        // the certification policy forbids further changes.
        WorkflowSigningService.WorkflowDocument doc = workflowSigningService.load(certified);
        assertThat(doc.effective()).isNotNull();
        assertThat(doc.effective().canSign()).isFalse();
        assertThat(doc.effective().signingBlockers().toString()).contains("certification");

        connectToken();
        Path output = dir.resolve("should-not-exist.pdf");
        assertThatThrownBy(() -> workflowSigningService.signNextStep(
                certified, null, output))
                .isInstanceOf(SignerException.class);
        assertThat(output).doesNotExist();
    }

    @Test
    void nonRestrictiveDocMdpDoesNotBlockWorkflowSigning() throws Exception {
        Path dir = Files.createTempDirectory("wf-docmdp-ok");
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);

        WorkflowManifest manifest = workflowSigningService.createWorkflow(
                original, "contract.pdf", List.of(aliceStep()));
        Path workflowPdf = dir.resolve("contract-wf.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, workflowPdf);

        // Certification with P=2 (form fill / signatures allowed) must NOT
        // block the workflow — the standard approval-signing policy.
        connectToken();
        Path certified = dir.resolve("certified.pdf");
        pdfSigningService.signPdf(workflowPdf, MockSigningToken.DEFAULT_ALIAS,
                SigningAlgorithm.RSA_SHA256, certified, null, null,
                doc -> {
                    COSDictionary mdp = new COSDictionary();
                    mdp.setInt(COSName.getPDFName("P"), 2);
                    COSDictionary perms = new COSDictionary();
                    perms.setItem(COSName.getPDFName("DocMDP"), mdp);
                    doc.getDocumentCatalog().getCOSObject()
                            .setItem(COSName.getPDFName("Perms"), perms);
                });

        SignatureVerificationResult result = verificationService.verifyPdf(certified);
        assertThat(result.signatures().get(0).docMdp()).isEqualTo(2);

        WorkflowSigningService.WorkflowDocument doc = workflowSigningService.load(certified);
        // Step 1 fulfilled by the direct signature; the workflow is simply
        // complete — no certification blocker added.
        assertThat(doc.effective().status())
                .isEqualTo(EffectiveWorkflowState.WorkflowStatus.COMPLETED);
        assertThat(doc.effective().signingBlockers().toString())
                .doesNotContain("certification");
    }

    /* ------------------------------------------------------------------
     * Placement bounds: out-of-page placements are rejected at signing
     * ------------------------------------------------------------------ */

    @Test
    void outOfBoundsPlacementIsRejectedAtSigningTime() throws Exception {
        Path dir = Files.createTempDirectory("wf-bounds");
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);

        // 2000 pt wide on a 612x792 page: the manifest carries a placement
        // that cannot fit (e.g. created against a different document).
        WorkflowManifest manifest = workflowSigningService.createWorkflow(
                original, "contract.pdf",
                List.of(WorkflowSignerStep.of(1, "Alice",
                        identityOf(aliasCertificate(MockSigningToken.DEFAULT_ALIAS)),
                        new SignaturePlacement(0, 100, 650, 2000, 60))));
        Path workflowPdf = dir.resolve("contract-wf.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, workflowPdf);

        connectToken();
        Path output = dir.resolve("should-not-exist.pdf");
        assertThatThrownBy(() -> workflowSigningService.signNextStep(
                workflowPdf, null, output))
                .isInstanceOf(SignerException.class);
        assertThat(output).doesNotExist();
    }

    /* ------------------------------------------------------------------
     * helpers
     * ------------------------------------------------------------------ */

    private WorkflowSignerStep aliceStep() {
        return WorkflowSignerStep.of(1, "Alice",
                identityOf(aliasCertificate(MockSigningToken.DEFAULT_ALIAS)),
                new SignaturePlacement(0, 100, 600, 180, 60));
    }

    private WorkflowSignerStep karimStep() {
        return WorkflowSignerStep.of(2, "Karim",
                identityOf(aliasCertificate(MockSigningToken.THIRD_ALIAS)),
                new SignaturePlacement(0, 320, 600, 180, 60));
    }

    private void connectToken() {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());
    }

    private SigningCertificate aliasCertificate(String alias) {
        connectToken();
        SigningCertificate certificate = tokenService.getCertificates().stream()
                .filter(c -> alias.equals(c.getAlias()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing alias " + alias));
        tokenService.logout();
        tokenService.disconnect();
        simulator.removeToken();
        return certificate;
    }

    private CertificateIdentity identityOf(SigningCertificate certificate) {
        return new CertificateIdentity(certificate.getSha256Fingerprint(),
                certificate.getSubject(), certificate.getIssuer(),
                certificate.getSerialNumber());
    }

    private byte[] concatenate(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void createOnePagePdf(Path target) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            page.setMediaBox(new PDRectangle(612, 792));
            doc.addPage(page);
            doc.save(target.toFile());
        }
    }
}

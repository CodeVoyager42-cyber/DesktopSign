package com.example.signer.workflow;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;
import com.example.signer.verification.SignatureVerificationResult;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phases 5–7 integration tests: the portable cross-PC workflow over REAL
 * PDFs, REAL signature revisions and the REAL verification chain.
 *
 * <p>Each "PC" is simulated by reloading the workflow PDF from disk through a
 * fresh {@link WorkflowSigningService#load} — no in-memory state carries over,
 * exactly the crash-recovery / cross-machine contract.</p>
 */
@SpringBootTest
class WorkflowCrossPcIntegrationTests {

    @Autowired
    private TokenService tokenService;

    @Autowired
    private MockTokenSimulator simulator;

    @Autowired
    private WorkflowSigningService workflowSigningService;

    @Autowired
    private com.example.signer.signing.PdfSigningService pdfSigningService;

    @Autowired
    private com.example.signer.verification.PdfVerificationService verificationService;

    @AfterEach
    void cleanUp() {
        tokenService.disconnect();
        simulator.removeToken();
    }

    /* ------------------------------------------------------------------
     * §34: the full definition-of-done scenario, three PCs, three revisions
     * ------------------------------------------------------------------ */

    @Test
    void threeSignerFlowAcrossThreePcsProducesCompletedIndependentlyVerifiablePdf()
            throws Exception {
        Path dir = Files.createTempDirectory("wf-crosspc");
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);

        WorkflowManifest manifest = threeSignerManifest("wf-crosspc-1");
        Path workflowPdf = dir.resolve("contract-wf.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, workflowPdf);

        // ── PC 1 (Alice) ─────────────────────────────────────────────
        connectToken();
        EffectiveWorkflowState state1 = workflowSigningService
                .load(workflowPdf).effective();
        assertThat(state1.status())
                .isEqualTo(EffectiveWorkflowState.WorkflowStatus.NOT_STARTED);
        assertThat(state1.currentStepOrder()).isEqualTo(1);

        Path afterAlice = dir.resolve("contract-alice.pdf");
        EffectiveWorkflowState s1 = workflowSigningService.signNextStep(
                workflowPdf, null, afterAlice);
        assertThat(s1.status())
                .isEqualTo(EffectiveWorkflowState.WorkflowStatus.IN_PROGRESS);
        assertThat(s1.currentStepOrder()).isEqualTo(2);

        // Revision 1: Alice's signature valid, chain reaches EOF.
        SignatureVerificationResult v1 = verificationService.verifyPdf(afterAlice);
        assertThat(v1.valid()).isTrue();
        assertThat(v1.signatures()).hasSize(1);

        // ── PC 2 (Karim): fresh load, nothing in memory ──────────────
        WorkflowSigningService.WorkflowDocument pc2 = workflowSigningService.load(afterAlice);
        assertThat(pc2.manifest()).isNotNull();
        EffectiveWorkflowState state2 = pc2.effective();
        assertThat(state2.status())
                .isEqualTo(EffectiveWorkflowState.WorkflowStatus.IN_PROGRESS);
        assertThat(state2.currentStepOrder()).isEqualTo(2);
        assertThat(state2.steps().get(0).fulfilled()).isTrue();
        assertThat(state2.steps().get(0).verified()).isTrue();
        assertThat(state2.steps().get(0).signedAt()).isNotNull();
        assertThat(state2.steps().get(1).yourTurn()).isTrue();
        assertThat(state2.steps().get(2).yourTurn()).isFalse();

        Path afterKarim = dir.resolve("contract-karim.pdf");
        EffectiveWorkflowState s2 = workflowSigningService.signNextStep(
                afterAlice, null, afterKarim);
        assertThat(s2.currentStepOrder()).isEqualTo(3);

        // Revisions 1 and 2 both valid.
        SignatureVerificationResult v2 = verificationService.verifyPdf(afterKarim);
        assertThat(v2.valid()).isTrue();
        assertThat(v2.signatures()).hasSize(2);

        // ── PC 3 (Sara) ──────────────────────────────────────────────
        WorkflowSigningService.WorkflowDocument pc3 = workflowSigningService.load(afterKarim);
        assertThat(pc3.effective().currentStepOrder()).isEqualTo(3);
        assertThat(pc3.effective().steps().get(2).yourTurn()).isTrue();

        Path afterSara = dir.resolve("contract-final.pdf");
        EffectiveWorkflowState s3 = workflowSigningService.signNextStep(
                afterKarim, null, afterSara);

        // ── final state ──────────────────────────────────────────────
        assertThat(s3.status())
                .isEqualTo(EffectiveWorkflowState.WorkflowStatus.COMPLETED);
        assertThat(s3.canSign()).isFalse();
        assertThat(s3.steps()).allSatisfy(step -> {
            assertThat(step.fulfilled()).isTrue();
            assertThat(step.verified()).isTrue();
        });

        // All three signatures valid in the final PDF, in manifest order.
        SignatureVerificationResult v3 = verificationService.verifyPdf(afterSara);
        assertThat(v3.valid()).isTrue();
        assertThat(v3.signatures()).hasSize(3);

        // Signatures were produced by the three DIFFERENT certificates.
        Map<String, Long> byFingerprint = v3.signatures().stream()
                .map(SignatureVerificationResult.SignatureDetail::signerCertFingerprint)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(byFingerprint).hasSize(3);

        // Completed workflow reloads as COMPLETED (crash recovery).
        EffectiveWorkflowState reopened = workflowSigningService
                .load(afterSara).effective();
        assertThat(reopened.status())
                .isEqualTo(EffectiveWorkflowState.WorkflowStatus.COMPLETED);

        // Manifest state agrees with the chain (no overclaim, no repair).
        assertThat(reopened.warnings()).isEmpty();
    }

    /* ------------------------------------------------------------------
     * §12: out-of-turn protection (domain layer, not just UI)
     * ------------------------------------------------------------------ */

    @Test
    void signingIsBlockedWhenWorkflowIsCompleted() throws Exception {
        Path dir = Files.createTempDirectory("wf-outofturn");
        Path workflowPdf = seedOneStepSigned(dir);

        connectToken();
        WorkflowSigningService.WorkflowDocument doc = workflowSigningService.load(workflowPdf);
        // One signer, already fulfilled → nothing to sign.
        assertThat(doc.effective().status())
                .isEqualTo(EffectiveWorkflowState.WorkflowStatus.COMPLETED);

        assertThatThrownBy(() -> workflowSigningService.requireCanSignNow(
                doc, doc.effective()))
                .isInstanceOf(SignerException.class)
                .extracting(e -> ((SignerException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORKFLOW_OUT_OF_TURN);

        Path output = dir.resolve("should-not-exist.pdf");
        assertThatThrownBy(() -> workflowSigningService.signNextStep(
                workflowPdf, null, output))
                .isInstanceOf(SignerException.class);
        assertThat(output).doesNotExist();
    }

    @Test
    void staleDocumentIsRejectedByTheFreshDiskRead() throws Exception {
        // PC A loads the workflow (step 1 pending). Meanwhile PC B advances
        // the SAME file on disk. PC A's sign attempt must re-read from disk —
        // its decision is made against the CURRENT bytes (Alice already
        // signed), never against the caller's stale in-memory view.
        Path dir = Files.createTempDirectory("wf-stale");
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);
        WorkflowManifest manifest = threeSignerManifest("wf-stale-1");
        Path workflowPdf = dir.resolve("contract-wf.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, workflowPdf);

        connectToken();

        // "PC B" advances the file: Alice signs, output replaces the shared file.
        Path pcBOutput = dir.resolve("contract-advanced.pdf");
        workflowSigningService.signNextStep(workflowPdf, null, pcBOutput);
        Files.copy(pcBOutput, workflowPdf, StandardCopyOption.REPLACE_EXISTING);

        // "PC A" (still holding the old NOT_STARTED view) signs now. The
        // domain re-reads the file and decides from the CURRENT bytes: it
        // advances to step 2 — it does NOT produce a second step-1 signature
        // against the stale view (which would be a duplicate signer).
        Path pcAOutput = dir.resolve("contract-a.pdf");
        EffectiveWorkflowState staleAttempt = workflowSigningService
                .signNextStep(workflowPdf, null, pcAOutput);
        assertThat(staleAttempt.currentStepOrder()).isEqualTo(3);

        SignatureVerificationResult vStale = verificationService.verifyPdf(pcAOutput);
        assertThat(vStale.valid()).isTrue();
        assertThat(vStale.signatures()).hasSize(2);
        assertThat(vStale.signatures().get(0).signerCertFingerprint())
                .isNotEqualTo(vStale.signatures().get(1).signerCertFingerprint());
    }

    /* ------------------------------------------------------------------
     * §13: certificate mismatch — no partial signature, input untouched
     * ------------------------------------------------------------------ */

    @Test
    void certificateMismatchBlocksSigningAndLeavesThePdfUntouched() throws Exception {
        Path dir = Files.createTempDirectory("wf-mismatch");
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);

        // Step 2 carries a fingerprint that exists on NO token.
        WorkflowManifest manifest = new WorkflowManifest(WorkflowManifest.CURRENT,
                "wf-mismatch-1", Instant.now(), Instant.now(), List.of(
                WorkflowSignerStep.of(1, "Alice",
                        identityOf(aliasCertificate(MockSigningToken.DEFAULT_ALIAS)),
                        new SignaturePlacement(0, 100, 650, 180, 60)),
                WorkflowSignerStep.of(2, "Impostor",
                        new CertificateIdentity(
                                "DE:AD:BE:EF:00:11:22:33:44:55:66:77:88:99:AA:BB"
                                        + ":CC:DD:EE:FF:01:23:45:67:89:AB:CD:EF:FE:DC:BA:98",
                                "CN=Impostor", "CN=Dev CA", "02"),
                        new SignaturePlacement(0, 320, 650, 180, 60))));
        Path workflowPdf = dir.resolve("contract-wf.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, workflowPdf);

        connectToken();
        byte[] before = Files.readAllBytes(workflowPdf);

        // Step 1 signs fine…
        Path afterAlice = dir.resolve("after-alice.pdf");
        workflowSigningService.signNextStep(workflowPdf, null, afterAlice);
        assertThat(verificationService.verifyPdf(afterAlice).valid()).isTrue();

        // …step 2 is refused: the connected token cannot satisfy the identity.
        byte[] beforeFailedAttempt = Files.readAllBytes(afterAlice);
        assertThatThrownBy(() -> workflowSigningService.signNextStep(
                afterAlice, null, dir.resolve("after-impostor.pdf")))
                .isInstanceOf(SignerException.class)
                .extracting(e -> ((SignerException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORKFLOW_CERT_MISMATCH);

        // §13: the PDF must remain byte-identical; no partial signature anywhere.
        assertThat(Files.readAllBytes(afterAlice))
                .isEqualTo(beforeFailedAttempt);
        assertThat(verificationService.verifyPdf(afterAlice).signatures()).hasSize(1);
    }

    /* ------------------------------------------------------------------
     * §20 scenario A: document tampered after a signature → signing blocked
     * ------------------------------------------------------------------ */

    @Test
    void tamperedDocumentIsRejectedWithSignaturesInvalid() throws Exception {
        Path dir = Files.createTempDirectory("wf-tamper");
        Path workflowPdf = seedOneStepSigned(dir);

        // Append junk AFTER the last signature → chain no longer reaches EOF.
        byte[] tampered = Files.readAllBytes(workflowPdf);
        Path broken = dir.resolve("tampered.pdf");
        Files.write(broken, concatenate(tampered,
                "\n%%tampered incremental junk".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));

        WorkflowSigningService.WorkflowDocument doc = workflowSigningService.load(broken);
        assertThat(doc.effective()).isNotNull();
        assertThat(doc.effective().status())
                .isEqualTo(EffectiveWorkflowState.WorkflowStatus.SIGNATURE_INVALID);
        assertThat(doc.effective().canSign()).isFalse();

        connectToken();
        Path output = dir.resolve("should-not-exist.pdf");
        assertThatThrownBy(() -> workflowSigningService.signNextStep(
                broken, null, output))
                .isInstanceOf(SignerException.class);
        assertThat(output).doesNotExist();
    }

    /* ------------------------------------------------------------------
     * §19: invalid / corrupted manifest handling
     * ------------------------------------------------------------------ */

    @Test
    void corruptedManifestSurfacesAsParseFailureNotCrash() throws Exception {
        Path dir = Files.createTempDirectory("wf-corrupt");
        Path plain = dir.resolve("plain.pdf");
        createOnePagePdf(plain);

        Path corrupted = dir.resolve("corrupted.pdf");
        try (var doc = org.apache.pdfbox.Loader.loadPDF(plain.toFile())) {
            WorkflowManifestStore.write(doc,
                    "{ this is not json ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            doc.save(corrupted.toFile());
        }

        WorkflowSigningService.WorkflowDocument doc = workflowSigningService.load(corrupted);
        assertThat(doc.parseFailure()).isNotNull();
        assertThat(doc.manifest()).isNull();

        connectToken();
        Path output = dir.resolve("should-not-exist.pdf");
        assertThatThrownBy(() -> workflowSigningService.signNextStep(
                corrupted, null, output))
                .isInstanceOf(SignerException.class)
                .extracting(e -> ((SignerException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORKFLOW_INVALID);
        assertThat(output).doesNotExist();
    }

    @Test
    void unsupportedFutureVersionIsRejectedGracefully() throws Exception {
        Path dir = Files.createTempDirectory("wf-version");
        Path plain = dir.resolve("plain.pdf");
        createOnePagePdf(plain);

        WorkflowManifest manifest = threeSignerManifest("wf-version-1");
        byte[] raw = WorkflowManifestCodec.serialize(manifest);
        String bumped = new String(raw, java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"workflowManifestVersion\":1", "\"workflowManifestVersion\":99");
        assertThat(bumped).contains("\"workflowManifestVersion\":99");

        Path future = dir.resolve("future.pdf");
        try (var doc = org.apache.pdfbox.Loader.loadPDF(plain.toFile())) {
            WorkflowManifestStore.write(doc,
                    bumped.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            doc.save(future.toFile());
        }

        WorkflowSigningService.WorkflowDocument doc = workflowSigningService.load(future);
        assertThat(doc.parseFailure()).isNotNull();
        assertThat(doc.parseFailure().getErrorCode())
                .isEqualTo(ErrorCode.WORKFLOW_UNSUPPORTED_VERSION);
        assertThat(doc.manifest()).isNull();

        connectToken();
        assertThatThrownBy(() -> workflowSigningService.signNextStep(
                future, null, dir.resolve("should-not-exist.pdf")))
                .isInstanceOf(SignerException.class)
                .extracting(e -> ((SignerException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORKFLOW_UNSUPPORTED_VERSION);
    }

    /* ------------------------------------------------------------------
     * §23: plain PDFs keep working — no workflow, no signing via workflow path
     * ------------------------------------------------------------------ */

    @Test
    void plainPdfWithoutWorkflowIsReportedAsSuch() throws Exception {
        Path dir = Files.createTempDirectory("wf-plain");
        Path plain = dir.resolve("plain.pdf");
        createOnePagePdf(plain);

        WorkflowSigningService.WorkflowDocument doc = workflowSigningService.load(plain);
        assertThat(doc.manifest()).isNull();
        assertThat(doc.parseFailure()).isNull();
        assertThat(doc.effective()).isNull();
        // Unsigned plain PDF: zero signatures, so chain "valid" is false by
        // definition — what matters is that nothing failed and nothing parsed.
        assertThat(doc.verification().signatures()).isEmpty();

        connectToken();
        assertThatThrownBy(() -> workflowSigningService.signNextStep(
                plain, null, dir.resolve("should-not-exist.pdf")))
                .isInstanceOf(SignerException.class)
                .extracting(e -> ((SignerException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORKFLOW_INVALID);
    }

    /* ------------------------------------------------------------------
     * §6: the domain gate requires an authenticated token
     * ------------------------------------------------------------------ */

    @Test
    void eligibilityRequiresAnAuthenticatedToken() throws Exception {
        Path dir = Files.createTempDirectory("wf-notoken");
        Path workflowPdf = seedOneStepSignedButPending(dir);

        WorkflowSigningService.WorkflowDocument doc = workflowSigningService.load(workflowPdf);
        // No token connected at all.
        assertThatThrownBy(() -> workflowSigningService.requireCanSignNow(
                doc, doc.effective()))
                .isInstanceOf(SignerException.class)
                .extracting(e -> ((SignerException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_NOT_CONNECTED);

        // Detected but not authenticated is equally insufficient.
        simulator.insertToken();
        tokenService.detect();
        assertThatThrownBy(() -> workflowSigningService.requireCanSignNow(
                doc, doc.effective()))
                .isInstanceOf(SignerException.class)
                .extracting(e -> ((SignerException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_NOT_CONNECTED);
    }

    /* ------------------------------------------------------------------
     * helpers
     * ------------------------------------------------------------------ */

    private void connectToken() {
        tokenService.disconnect();
        simulator.removeToken();
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());
    }

    /** One-signer workflow whose only step is ALREADY fulfilled (completed). */
    private Path seedOneStepSigned(Path dir) throws Exception {
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);
        WorkflowManifest manifest = new WorkflowManifest(WorkflowManifest.CURRENT,
                "wf-single-" + System.nanoTime(), Instant.now(), Instant.now(),
                List.of(WorkflowSignerStep.of(1, "Alice",
                        identityOf(aliasCertificate(MockSigningToken.DEFAULT_ALIAS)),
                        new SignaturePlacement(0, 100, 650, 180, 60))));
        Path workflowPdf = dir.resolve("contract-wf.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, workflowPdf);
        connectToken();
        Path signed = dir.resolve("contract-signed.pdf");
        workflowSigningService.signNextStep(workflowPdf, null, signed);
        return signed;
    }

    /** One-signer workflow with its only step still pending. */
    private Path seedOneStepSignedButPending(Path dir) throws Exception {
        Path original = dir.resolve("contract.pdf");
        createOnePagePdf(original);
        WorkflowManifest manifest = new WorkflowManifest(WorkflowManifest.CURRENT,
                "wf-pending-" + System.nanoTime(), Instant.now(), Instant.now(),
                List.of(WorkflowSignerStep.of(1, "Alice",
                        identityOf(aliasCertificate(MockSigningToken.DEFAULT_ALIAS)),
                        new SignaturePlacement(0, 100, 650, 180, 60))));
        Path workflowPdf = dir.resolve("contract-wf.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, workflowPdf);
        return workflowPdf;
    }

    /**
     * Three-step manifest bound to the three REAL mock-token certificates
     * (RSA, RSA-third, EC) — Sara's step exercises the ECDSA path.
     */
    private WorkflowManifest threeSignerManifest(String workflowId) {
        connectToken();
        Map<String, SigningCertificate> byAlias = tokenService.getCertificates()
                .stream()
                .collect(Collectors.toMap(SigningCertificate::getAlias,
                        Function.identity()));
        tokenService.logout();
        tokenService.disconnect();
        simulator.removeToken();

        return new WorkflowManifest(WorkflowManifest.CURRENT, workflowId,
                Instant.now(), Instant.now(), List.of(
                WorkflowSignerStep.of(1, "Alice",
                        identityOf(byAlias.get(MockSigningToken.DEFAULT_ALIAS)),
                        new SignaturePlacement(0, 100, 650, 180, 60)),
                WorkflowSignerStep.of(2, "Karim",
                        identityOf(byAlias.get(MockSigningToken.THIRD_ALIAS)),
                        new SignaturePlacement(0, 320, 650, 180, 60)),
                WorkflowSignerStep.of(3, "Sara",
                        identityOf(byAlias.get(MockSigningToken.EC_ALIAS)),
                        new SignaturePlacement(0, 400, 650, 180, 60))));
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
            doc.addPage(new PDPage());
            doc.save(target.toFile());
        }
    }
}

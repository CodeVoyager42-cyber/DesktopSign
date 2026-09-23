package com.example.signer.workflow;

import com.example.signer.signing.SignaturePlacement;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.token.mock.MockTokenSimulator;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-4 integration tests: manifest embedded as a PDF embedded file, with
 * zero impact on signatures — before signing, and inside signing revisions.
 */
@SpringBootTest
class WorkflowManifestStoreIntegrationTests {

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

    @Test
    void manifestSurvivesPdfRoundTripInAnUnsignedDocument() throws Exception {
        Path dir = Files.createTempDirectory("wf-store");
        Path original = dir.resolve("plain.pdf");
        createOnePagePdf(original);

        WorkflowManifest manifest = manifestFor("wf-store-1");
        Path target = dir.resolve("with-manifest.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, target);

        // Original untouched.
        assertThat(WorkflowManifestStore.readRaw(original)).isNull();

        var parse = WorkflowManifestStore.tryRead(target);
        assertThat(parse).isNotNull();
        assertThat(parse.ok()).isTrue();
        assertThat(parse.manifest().workflowId()).isEqualTo("wf-store-1");
        assertThat(parse.manifest().stepsInOrder()).hasSize(2);
        assertThat(parse.manifest().stepsInOrder().get(1).placement().pageIndex())
                .isEqualTo(1);
        // The original carries no manifest.
        assertThat(WorkflowManifestStore.tryRead(original)).isNull();
    }

    @Test
    void embeddingManifestDoesNotBreakLaterSignatureChain() throws Exception {
        Path dir = Files.createTempDirectory("wf-store-sign");
        Path original = dir.resolve("plain.pdf");
        createOnePagePdf(original);

        WorkflowManifest manifest = manifestFor("wf-store-2");
        Path seeded = dir.resolve("seeded.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, seeded);

        // Sign the seeded document (single invisible signature).
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        Path signed = dir.resolve("signed.pdf");
        pdfSigningService.signPdf(seeded, MockSigningToken.DEFAULT_ALIAS,
                com.example.signer.token.SigningAlgorithm.RSA_SHA256, signed);

        var verification = verificationService.verifyPdf(signed);
        assertThat(verification.valid())
                .as("signature over manifest-bearing document must verify: %s",
                        verification.message())
                .isTrue();

        // The manifest still reads back from the signed document.
        assertThat(WorkflowManifestStore.tryRead(signed).ok()).isTrue();
    }

    @Test
    void manifestUpdateInsideSigningRevisionKeepsChainValidAndReachable() throws Exception {
        Path dir = Files.createTempDirectory("wf-store-rev");
        Path original = dir.resolve("plain.pdf");
        createOnePagePdf(original);

        WorkflowManifest manifest = manifestFor("wf-store-3");
        Path seeded = dir.resolve("seeded.pdf");
        workflowSigningService.writeManifestIntoCopy(original, manifest, seeded);

        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(MockSigningToken.defaultPin());

        // Sign WITH the manifest-update customizer: manifest v2 (step 1 done)
        // lands in the same revision as the signature.
        WorkflowManifest updated = new WorkflowManifest(manifest.version(),
                manifest.workflowId(), manifest.createdAt(), Instant.now(),
                List.of(
                        manifest.stepsInOrder().get(0).withSignedAt(Instant.now())
                                .withRevisionIndex(0),
                        manifest.stepsInOrder().get(1)));

        Path signed = dir.resolve("signed.pdf");
        pdfSigningService.signPdf(seeded, MockSigningToken.DEFAULT_ALIAS,
                com.example.signer.token.SigningAlgorithm.RSA_SHA256, signed,
                null, null, WorkflowManifestStore.writer(updated));

        // Chain still valid AND reaches EOF (nothing appended after).
        var verification = verificationService.verifyPdf(signed);
        assertThat(verification.valid()).isTrue();
        assertThat(verification.signatures()).hasSize(1);

        // Reconciliation sees step 1 fulfilled by the real signature.
        var doc = workflowSigningService.load(signed);
        assertThat(doc.manifest()).isNotNull();
        assertThat(doc.effective()).isNotNull();
        assertThat(doc.effective().status())
                .isEqualTo(EffectiveWorkflowState.WorkflowStatus.IN_PROGRESS);
        assertThat(doc.effective().currentStepOrder()).isEqualTo(2);
        assertThat(doc.effective().steps().get(0).fulfilled()).isTrue();
        assertThat(doc.effective().steps().get(1).fulfilled()).isFalse();
    }

    @Test
    void readRawReturnsNullForWorkflowsFreeDocuments() throws Exception {
        Path dir = Files.createTempDirectory("wf-store-plain");
        Path plain = dir.resolve("plain.pdf");
        createOnePagePdf(plain);
        assertThat(WorkflowManifestStore.readRaw(plain)).isNull();
        assertThat(WorkflowManifestStore.tryRead(plain)).isNull();
    }

    /* ---------------- helpers ---------------- */

    /**
     * Builds a two-step manifest from the REAL certificates of the mock
     * token (its first two aliases), so reconciliation can map actual
     * signatures onto steps by fingerprint — the same path production uses.
     */
    private WorkflowManifest manifestFor(String workflowId) {
        simulator.insertToken();
        tokenService.detect();
        tokenService.startAuthentication();
        tokenService.authenticate(com.example.signer.token.mock.MockSigningToken.defaultPin());
        var certs = tokenService.getCertificates();
        var byAlias = certs.stream().collect(java.util.stream.Collectors
                .toMap(com.example.signer.token.SigningCertificate::getAlias, c -> c));
        CertificateIdentity alice = identityOf(
                byAlias.get(com.example.signer.token.mock.MockSigningToken.DEFAULT_ALIAS),
                "Alice");
        CertificateIdentity karim = identityOf(
                byAlias.get(com.example.signer.token.mock.MockSigningToken.THIRD_ALIAS),
                "Karim");
        tokenService.logout();
        tokenService.disconnect();
        simulator.removeToken();
        return new WorkflowManifest(WorkflowManifest.CURRENT, workflowId,
                Instant.now(), Instant.now(), List.of(
                WorkflowSignerStep.of(1, "Alice", alice,
                        new SignaturePlacement(0, 100, 650, 180, 60)),
                WorkflowSignerStep.of(2, "Karim", karim,
                        new SignaturePlacement(1, 320, 650, 180, 60))));
    }

    private CertificateIdentity identityOf(
            com.example.signer.token.SigningCertificate certificate, String displayName) {
        return new CertificateIdentity(certificate.getSha256Fingerprint(),
                certificate.getSubject(), certificate.getIssuer(),
                certificate.getSerialNumber());
    }

    private void createOnePagePdf(Path target) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(target.toFile());
        }
    }
}

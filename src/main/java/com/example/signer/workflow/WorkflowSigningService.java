package com.example.signer.workflow;

import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.signing.PdfSigningService;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.token.SigningCertificate;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.verification.PdfVerificationService;
import com.example.signer.verification.SignatureVerificationResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Domain service for the portable cross-PC signing workflow.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>create a workflow (manifest) for a document and its ordered signers</li>
 *   <li>persist the manifest into a copy of the PDF (before the first
 *       signature) as an embedded file</li>
 *   <li>determine the effective state (manifest ⨯ signature chain) via
 *       {@link WorkflowReconciliationService}</li>
 *   <li>decide whether the currently connected token may sign the next step
 *       (identity by certificate SHA-256 fingerprint — never by name)</li>
 *   <li>execute one signing step: validate eligibility, sign, embed the
 *       updated manifest inside the SAME incremental revision as the
 *       signature, re-open and verify the result, then atomically export</li>
 * </ul>
 *
 * <p><b>Ordering guarantee.</b> The updated manifest is written by the
 * {@code DocumentCustomizer} hook, so it is serialised by the same
 * {@code saveIncremental} that appends the signature: the new signature's
 * byte range covers the manifest state it announces, previous signatures stay
 * valid, and the chain still reaches end-of-file.
 *
 * <p><b>Atomicity.</b> All work happens on a temp file next to the output;
 * the original input is never touched; the validated result is moved into
 * place only after a full re-verification pass.
 */
@Service
public class WorkflowSigningService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSigningService.class);

    private final TokenService tokenService;
    private final PdfSigningService pdfSigningService;
    private final PdfVerificationService pdfVerificationService;
    private final WorkflowReconciliationService reconciliationService;

    public WorkflowSigningService(TokenService tokenService,
                                  PdfSigningService pdfSigningService,
                                  PdfVerificationService pdfVerificationService,
                                  WorkflowReconciliationService reconciliationService) {
        this.tokenService = tokenService;
        this.pdfSigningService = pdfSigningService;
        this.pdfVerificationService = pdfVerificationService;
        this.reconciliationService = reconciliationService;
    }

    /* ------------------------------------------------------------------
     * Creation
     * ------------------------------------------------------------------ */

    /**
     * Builds a manifest for the ordered signers. Identities must come from
     * real certificates (token or file); call
     * {@link #identityOf(SigningCertificate)} / {@link #identityOf(X509Certificate)}.
     */
    public WorkflowManifest createWorkflow(String documentName,
                                           List<WorkflowSignerStep> orderedSteps) {
        if (orderedSteps == null || orderedSteps.isEmpty()) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "A workflow needs at least one signer");
        }
        Instant now = Instant.now();
        return new WorkflowManifest(WorkflowManifest.CURRENT,
                UUID.randomUUID().toString(), now, now, List.copyOf(orderedSteps));
    }

    /**
     * Writes {@code manifest} into {@code sourcePdf} and produces
     * {@code targetPdf} — a copy of the original with the workflow embedded
     * and zero signatures touched (used before the first signature; after
     * signatures exist, the manifest only ever moves inside a signing
     * revision).
     */
    public void writeManifestIntoCopy(Path sourcePdf, WorkflowManifest manifest,
                                      Path targetPdf) {
        try {
            byte[] bytes = WorkflowManifestCodec.serialize(manifest);
            try (var doc = org.apache.pdfbox.Loader.loadPDF(sourcePdf.toFile())) {
                WorkflowManifestStore.write(doc, bytes);
                try (var os = Files.newOutputStream(targetPdf)) {
                    doc.save(os);
                }
            }
        } catch (IOException e) {
            throw new SignerException(ErrorCode.PDF_SIGNING_FAILED,
                    "The workflow could not be embedded into the document", e);
        }
    }

    /* ------------------------------------------------------------------
     * Reading
     * ------------------------------------------------------------------ */

    /** Loads and reconciles the workflow of the given PDF. Never throws for content. */
    public WorkflowDocument load(Path pdf) {
        WorkflowManifestCodec.ParseResult parse = WorkflowManifestStore.tryRead(pdf);
        SignatureVerificationResult verification;
        try {
            verification = pdfVerificationService.verifyPdf(pdf);
        } catch (Exception e) {
            log.warn("Verification of {} failed", pdf.getFileName(), e);
            throw new SignerException(ErrorCode.SIGNATURE_VERIFICATION_FAILED,
                    "The document could not be verified", e);
        }

        if (parse == null) {
            // Normal PDF — no workflow attached.
            return new WorkflowDocument(pdf, null, null, verification, List.of());
        }
        if (!parse.ok()) {
            // Manifest exists but is broken/unsupported.
            return new WorkflowDocument(pdf, null, parse.failure(), verification, List.of());
        }
        WorkflowManifest manifest = parse.manifest();
        EffectiveWorkflowState effective = manifest.isSupportedForSigning()
                ? reconciliationService.reconcile(manifest, verification)
                : null;
        return new WorkflowDocument(pdf, manifest, null, verification, effective == null
                ? List.of()
                : List.of(effective));
    }

    /* ------------------------------------------------------------------
     * Eligibility
     * ------------------------------------------------------------------ */

    /**
     * Validates that the token currently connected to {@code tokenService} may
     * sign the next step of {@code state}. Throws {@link SignerException} with
     * a user-safe message otherwise. This is the domain gate; the UI must call
     * it before enabling signing and the sign path calls it again.
     */
    public void requireCanSignNow(WorkflowDocument document, EffectiveWorkflowState state) {
        if (state == null || state.manifest() == null) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "This document does not contain a signing workflow");
        }
        if (!state.canSign()) {
            throw new SignerException(ErrorCode.WORKFLOW_OUT_OF_TURN,
                    state.signingBlockers().isEmpty()
                            ? "Signing is not allowed right now"
                            : String.join(" ", state.signingBlockers()));
        }
        WorkflowSignerStep step = state.currentStep();
        if (step == null) {
            throw new SignerException(ErrorCode.WORKFLOW_OUT_OF_TURN,
                    "There is no pending signature step in this workflow");
        }
        // Identity check against the CONNECTED token, not a UI selection.
        certificateForStep(step);
    }

    /**
     * The connected token's certificate assigned to {@code step}, located by
     * SHA-256 fingerprint. The domain gate: identity by certificate, never by
     * display name, and indifferent to how many other certificates the token
     * carries (they are simply not eligible here).
     */
    private SigningCertificate certificateForStep(WorkflowSignerStep step) {
        // AUTHENTICATED is the pre-sign state; SIGNATURE_COMPLETE means a
        // signing operation already ran in this session — the token is still
        // authenticated and further signatures are allowed (sequential
        // multi-signer use on one machine). Both are eligible here.
        if (!tokenService.isAuthenticated()
                && tokenService.getState() != TokenState.SIGNATURE_COMPLETE) {
            throw new SignerException(ErrorCode.TOKEN_NOT_CONNECTED,
                    "The signing token is not connected or not authenticated");
        }
        List<SigningCertificate> certs;
        try {
            certs = tokenService.getCertificates();
        } catch (IllegalStateException e) {
            throw new SignerException(ErrorCode.TOKEN_NOT_CONNECTED,
                    "The signing token is not connected or not authenticated");
        }
        if (certs == null || certs.isEmpty()) {
            throw new SignerException(ErrorCode.CERTIFICATE_NOT_FOUND,
                    "The connected token carries no signing certificate");
        }
        return certs.stream()
                .filter(c -> step.certificate()
                        .matchesFingerprint(c.getSha256Fingerprint()))
                .findFirst()
                .orElseThrow(() -> {
                    log.info("Certificate mismatch for workflow step {}: expected {}",
                            step.order(), step.certificate().fingerprintShort());
                    return new SignerException(ErrorCode.WORKFLOW_CERT_MISMATCH,
                            "The connected signing certificate does not match the"
                                    + " certificate assigned to this workflow step (expected "
                                    + step.certificate().fingerprintShort() + ")");
                });
    }

    /* ------------------------------------------------------------------
     * Signing
     * ------------------------------------------------------------------ */

    /**
     * Signs the current step with the connected token.
     *
     * <p>Sequence (see class docs for the ordering guarantee):
     * reload → reconcile → eligibility → sign+manifest in one revision →
     * reopen → verify all → atomic move to {@code outputPath}.
     *
     * @return the effective state of the signed result
     */
    public EffectiveWorkflowState signNextStep(Path workflowPdf,
                                               SignatureAppearanceHint appearanceHint,
                                               Path outputPath) {
        // 1-2: fresh read of the CURRENT file (never a cached state — the file
        // may have changed since it was displayed).
        WorkflowDocument fresh = load(workflowPdf);
        if (fresh.parseFailure() != null) {
            throw fresh.parseFailure();
        }
        if (fresh.manifest() == null) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "This document does not contain a signing workflow");
        }
        WorkflowManifest manifest = fresh.manifest();
        EffectiveWorkflowState before = fresh.effective();
        // 3: the domain gate — current turn, chain validity, certificate match.
        requireCanSignNow(fresh, before);
        WorkflowSignerStep step = before.currentStep();

        // 4: base file for this revision is the workflow PDF as read.
        Path base = fresh.pdf();
        if (before.status() == EffectiveWorkflowState.WorkflowStatus.NOT_STARTED
                && !hasManifestBytes(workflowPdf)) {
            // Creation-time edge: manifest announced but never embedded.
            // (writeManifestIntoCopy is the normal path; this guards direct
            // sign-on-creation use.)
            Path seeded = seedManifest(workflowPdf, manifest);
            base = seeded;
        }

        // 5: updated manifest with this step marked done (metadata only —
        // reconciliation will confirm against the chain after signing).
        Instant signedAt = Instant.now();
        WorkflowManifest updated = withStepFulfilled(manifest, step, signedAt);

        // 6-7: sign and embed in ONE incremental revision.
        Path temp = tempSibling(outputPath);
        try {
            String alias = keyAliasFor(step);
            com.example.signer.signing.SignaturePlacement placement = step.placement();
            com.example.signer.signing.SignatureAppearance appearance =
                    buildAppearance(step, appearanceHint);
            pdfSigningService.signPdf(base, alias,
                    algorithmFor(step), temp, placement, appearance,
                    WorkflowManifestStore.writer(updated));
            log.info("Workflow step {} signed into revision {} (manifest v{} embedded)",
                    step.order(), temp.getFileName(), updated.version());

            // 8: reopen and verify EVERYTHING before the result exists.
            WorkflowDocument check = load(temp);
            if (check.parseFailure() != null) {
                throw check.parseFailure();
            }
            if (check.effective() == null
                    || !check.effective().canSign()
                            && check.effective().status()
                                    != EffectiveWorkflowState.WorkflowStatus.COMPLETED) {
                throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                        "The signed result is not a valid workflow document");
            }
            if (!check.verification().valid()) {
                throw new SignerException(ErrorCode.SIGNATURE_VERIFICATION_FAILED,
                        "The new signature did not verify: "
                                + check.verification().message());
            }
            EffectiveWorkflowState after = check.effective();
            boolean stepNowFulfilled = after != null && after.steps().stream()
                    .filter(v -> v.order() == step.order())
                    .findFirst()
                    .map(EffectiveWorkflowState.StepView::fulfilled)
                    .orElse(false);
            if (!stepNowFulfilled) {
                throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                        "The workflow state was not advanced by the new signature");
            }

            // 9: validated — expose atomically. Never touch the input.
            atomicMove(temp, outputPath);
            log.info("Workflow PDF advanced: step {} signed, output {}",
                    step.order(), outputPath.getFileName());
            return after;
        } catch (SignerException e) {
            deleteQuietly(temp);
            throw e;
        } catch (Exception e) {
            deleteQuietly(temp);
            throw new SignerException(ErrorCode.PDF_SIGNING_FAILED,
                    "Signing failed: the document was not modified", e);
        }
    }

    /* ------------------------------------------------------------------
     * helpers
     * ------------------------------------------------------------------ */

    private boolean hasManifestBytes(Path pdf) {
        try {
            return WorkflowManifestStore.readRaw(pdf) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private Path seedManifest(Path pdf, WorkflowManifest manifest) {
        Path seeded = tempSibling(pdf);
        writeManifestIntoCopy(pdf, manifest, seeded);
        return seeded;
    }

    private WorkflowManifest withStepFulfilled(WorkflowManifest manifest,
                                               WorkflowSignerStep step, Instant at) {
        List<WorkflowSignerStep> updated = new ArrayList<>();
        int highestRevision = -1;
        for (WorkflowSignerStep s : manifest.stepsInOrder()) {
            if (s.revisionIndex() != null) {
                highestRevision = Math.max(highestRevision, s.revisionIndex());
            }
        }
        for (WorkflowSignerStep s : manifest.stepsInOrder()) {
            if (s.order() == step.order()) {
                updated.add(s.withSignedAt(at).withRevisionIndex(highestRevision + 1));
            } else {
                updated.add(s);
            }
        }
        return new WorkflowManifest(manifest.version(), manifest.workflowId(),
                manifest.createdAt(), Instant.now(), updated);
    }

    private String keyAliasFor(WorkflowSignerStep step) {
        return certificateForStep(step).getAlias();
    }

    /** RSA or ECDSA depending on the step certificate's key type. */
    private com.example.signer.token.SigningAlgorithm algorithmFor(WorkflowSignerStep step) {
        String keyAlgorithm = certificateForStep(step).getPublicKeyAlgorithm();
        return "EC".equals(keyAlgorithm)
                ? com.example.signer.token.SigningAlgorithm.ECDSA_SHA256
                : com.example.signer.token.SigningAlgorithm.RSA_SHA256;
    }

    private com.example.signer.signing.SignatureAppearance buildAppearance(
            WorkflowSignerStep step, SignatureAppearanceHint hint) {
        return new com.example.signer.signing.SignatureAppearance(
                hint == null || hint.reason() == null ? "Document signed with SignFlow" : hint.reason(),
                hint == null ? null : hint.location(),
                true, true, true, step.displayName());
    }

    private Path tempSibling(Path target) {
        try {
            return Files.createTempFile(
                    target.toAbsolutePath().getParent(),
                    target.getFileName().toString(),
                    ".tmp");
        } catch (IOException e) {
            throw new SignerException(ErrorCode.PDF_SIGNING_FAILED,
                    "Could not create a temporary file next to the output", e);
        }
    }

    private void atomicMove(Path from, Path to) {
        try {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new SignerException(ErrorCode.PDF_SIGNING_FAILED,
                    "The signed document could not be written to its destination", e);
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp file cleanup is best-effort
        }
    }

    /* ------------------------------------------------------------------
     * value types
     * ------------------------------------------------------------------ */

    /** A workflow PDF with its manifest, verification and effective state. */
    public record WorkflowDocument(
            Path pdf,
            WorkflowManifest manifest,
            SignerException parseFailure,
            SignatureVerificationResult verification,
            List<EffectiveWorkflowState> effectiveStates) {

        /** The reconciliation result, or {@code null} (none/unsupported). */
        public EffectiveWorkflowState effective() {
            return effectiveStates.isEmpty() ? null : effectiveStates.get(0);
        }
    }

    /** Optional /Reason and /Location for the signature dictionary. */
    public record SignatureAppearanceHint(String reason, String location) {
    }
}

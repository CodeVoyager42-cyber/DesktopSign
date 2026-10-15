package com.example.signer.workflow;

import com.example.signer.signing.SignatureAppearance;
import com.example.signer.token.SigningAlgorithm;
import org.apache.pdfbox.Loader;
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
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.HexFormat;
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
     * Builds a manifest for the ordered signers, bound to the SHA-256 hash of
     * {@code originalPdf} so the workflow can never silently accept a
     * different document. Identities must come from real certificates (token
     * or file); call {@link #identityOf(SigningCertificate)} /
     * {@link #identityOf(X509Certificate)}.
     */
    public WorkflowManifest createWorkflow(Path originalPdf,
                                           String documentName,
                                           List<WorkflowSignerStep> orderedSteps) {
        return createWorkflow(originalPdf, documentName,
                WorkflowSigningPolicy.SEQUENTIAL, orderedSteps);
    }

    /**
     * As {@link #createWorkflow(Path, String, List)} with an explicit signing
     * policy. The policy is embedded in the manifest so offline verification
     * and local signing enforce the same turn rules as the server.
     */
    public WorkflowManifest createWorkflow(Path originalPdf,
                                           String documentName,
                                           WorkflowSigningPolicy signingPolicy,
                                           List<WorkflowSignerStep> orderedSteps) {
        if (orderedSteps == null || orderedSteps.isEmpty()) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "A workflow needs at least one signer");
        }
        Instant now = Instant.now();
        return new WorkflowManifest(WorkflowManifest.CURRENT,
                UUID.randomUUID().toString(), now, now,
                sha256Hex(originalPdf),
                signingPolicy == null ? WorkflowSigningPolicy.SEQUENTIAL : signingPolicy,
                List.copyOf(orderedSteps));
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
            // Document binding: when the manifest is bound to an original
            // document, refuse to embed it into different bytes. This is the
            // gate against seeding a workflow with a substituted document.
            if (manifest.originalDocumentSha256() != null
                    && !manifest.originalDocumentSha256().equals(sha256Hex(sourcePdf))) {
                throw new SignerException(ErrorCode.WORKFLOW_TAMPERED,
                        "The document does not match the workflow's registered"
                                + " original document (SHA-256 mismatch)");
            }
            byte[] bytes = WorkflowManifestCodec.serialize(manifest);
            try (var doc = Loader.loadPDF(sourcePdf.toFile())) {
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
    public WorkflowSignerStep requireCanSignNow(WorkflowDocument document,
                                                EffectiveWorkflowState state) {
        return requireCanSignNow(document, state, null);
    }

    /**
     * As {@link #requireCanSignNow(WorkflowDocument, EffectiveWorkflowState)}
     * but with an optional expected signer order — the signer named by the
     * coordination server's claimed turn — used to disambiguate when the
     * connected token carries several certificates that match different
     * pending steps.
     */
    public WorkflowSignerStep requireCanSignNow(WorkflowDocument document,
                                                EffectiveWorkflowState state,
                                                Integer expectedSignerOrder) {
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
        if (step == null
                && state.manifest().policy() == WorkflowSigningPolicy.FLEXIBLE) {
            // FLEXIBLE: no manifest-defined turn — the connected token's
            // certificate selects which pending step may sign now. When the
            // server has named the claimed signer, that order wins over the
            // manifest's step order (one token can carry several certs).
            step = flexibleStepForConnectedToken(state, expectedSignerOrder);
        }
        if (step == null) {
            throw new SignerException(ErrorCode.WORKFLOW_OUT_OF_TURN,
                    "There is no pending signature step for the connected"
                            + " signing certificate in this workflow");
        }
        // Identity check against the CONNECTED token, not a UI selection.
        certificateForStep(step);
        return step;
    }

    /**
     * The pending step fulfilled by a certificate on the connected token, or
     * {@code null} when the token holds none of the pending signers.
     */
    private WorkflowSignerStep flexibleStepForConnectedToken(
            EffectiveWorkflowState state, Integer expectedSignerOrder) {
        List<SigningCertificate> certs = connectedCertificates();
        List<WorkflowSignerStep> pending = state.manifest().stepsInOrder().stream()
                .filter(s -> s.revisionIndex() == null)
                .toList();
        if (expectedSignerOrder != null) {
            // The claimed turn names the signer: a multi-certificate token can
            // match several pending steps, so the claim disambiguates.
            return pending.stream()
                    .filter(s -> expectedSignerOrder.equals(s.order()))
                    .filter(s -> certs.stream().anyMatch(c -> s.certificate()
                            .matchesFingerprint(c.getSha256Fingerprint())))
                    .findFirst()
                    .orElse(null);
        }
        for (WorkflowSignerStep step : pending) {
            boolean claimed = certs.stream().anyMatch(c ->
                    step.certificate().matchesFingerprint(c.getSha256Fingerprint()));
            if (claimed) {
                return step;
            }
        }
        return null;
    }

    /**
     * The connected token's certificate assigned to {@code step}, located by
     * SHA-256 fingerprint. The domain gate: identity by certificate, never by
     * display name, and indifferent to how many other certificates the token
     * carries (they are simply not eligible here).
     */
    private SigningCertificate certificateForStep(WorkflowSignerStep step) {
        List<SigningCertificate> certs = connectedCertificates();
        SigningCertificate certificate = certs.stream()
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
        // Fail closed: an expired or not-yet-valid certificate must not
        // produce new signatures.
        try {
            certificate.getCertificate().checkValidity();
        } catch (CertificateExpiredException e) {
            throw new SignerException(ErrorCode.CERTIFICATE_EXPIRED,
                    "The signing certificate has expired and can no longer be"
                            + " used to sign");
        } catch (CertificateNotYetValidException e) {
            throw new SignerException(ErrorCode.CERTIFICATE_NOT_YET_VALID,
                    "The signing certificate is not valid yet");
        }
        return certificate;
    }

    /**
     * Certificates of the connected, authenticated token. AUTHENTICATED is
     * the pre-sign state; SIGNATURE_COMPLETE means a signing operation
     * already ran in this session — the token is still authenticated and
     * further signatures are allowed (multi-signer use on one machine).
     */
    private List<SigningCertificate> connectedCertificates() {
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
        return certs;
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
        return signNextStep(workflowPdf, appearanceHint, outputPath, null);
    }

    /**
     * As {@link #signNextStep(Path, SignatureAppearanceHint, Path)} but with
     * the signer order expected by the coordination server's claimed turn —
     * required when one token carries certificates for several pending
     * signers and a FLEXIBLE policy leaves the signing order open.
     */
    public EffectiveWorkflowState signNextStep(Path workflowPdf,
                                               SignatureAppearanceHint appearanceHint,
                                               Path outputPath,
                                               Integer expectedSignerOrder) {
        return signNextStep(workflowPdf, appearanceHint, outputPath,
                expectedSignerOrder, null);
    }

    /**
     * As above, additionally stamping {@code placementHint} (the signer's
     * chosen visible-signature geometry in PDF points) onto the fulfilled
     * step BEFORE the updated manifest is embedded — so the coordination
     * server can read the accepted placement back out of the revision it
     * stores (brief §33: durable placement metadata). When the manifest step
     * already carries a placement (workflow created with one), the existing
     * value wins.
     */
    public EffectiveWorkflowState signNextStep(Path workflowPdf,
                                               SignatureAppearanceHint appearanceHint,
                                               Path outputPath,
                                               Integer expectedSignerOrder,
                                               SignaturePlacement placementHint) {
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
        WorkflowSignerStep step = requireCanSignNow(fresh, before, expectedSignerOrder);

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
        WorkflowSignerStep stampable = step.placement() == null && placementHint != null
                ? step.withPlacement(placementHint)
                : step;
        WorkflowManifest updated = withStepFulfilled(manifest, stampable, signedAt);

        // 6-7: sign and embed in ONE incremental revision.
        Path temp = tempSibling(outputPath);
        try {
            String alias = keyAliasFor(step);
            SignaturePlacement placement =
                    stampable.placement();
            SignatureAppearance appearance =
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

    /** Lower-case hex SHA-256 of a file's bytes (streamed, bounded memory). */
    private static String sha256Hex(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new SignerException(ErrorCode.PDF_SIGNING_FAILED,
                    "The document could not be read to verify its identity", e);
        }
    }

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
                // Carry the placement chosen for this signature (brief §33):
                // without it the manifest never recorded where a remote
                // signer's box went, so the server and later signers could
                // not see it. A placement fixed at creation still wins.
                WorkflowSignerStep base = s.placement() == null && step.placement() != null
                        ? s.withPlacement(step.placement()) : s;
                updated.add(base.withSignedAt(at).withRevisionIndex(highestRevision + 1));
            } else {
                updated.add(s);
            }
        }
        return new WorkflowManifest(manifest.version(), manifest.workflowId(),
                manifest.createdAt(), Instant.now(),
                manifest.originalDocumentSha256(), manifest.policy(), updated);
    }

    private String keyAliasFor(WorkflowSignerStep step) {
        return certificateForStep(step).getAlias();
    }

    /** RSA or ECDSA depending on the step certificate's key type. */
    private SigningAlgorithm algorithmFor(WorkflowSignerStep step) {
        String keyAlgorithm = certificateForStep(step).getPublicKeyAlgorithm();
        return "EC".equals(keyAlgorithm)
                ? SigningAlgorithm.ECDSA_SHA256
                : SigningAlgorithm.RSA_SHA256;
    }

    private SignatureAppearance buildAppearance(
            WorkflowSignerStep step, SignatureAppearanceHint hint) {
        return new SignatureAppearance(
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

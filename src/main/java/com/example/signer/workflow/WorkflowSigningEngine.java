package com.example.signer.workflow;

import com.example.signer.signing.PdfSigningService;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.signing.SigningResult;
import com.example.signer.token.SigningAlgorithm;
import com.example.signer.token.TokenService;
import com.example.signer.token.TokenState;
import com.example.signer.verification.PdfVerificationService;
import com.example.signer.verification.SignatureVerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sequential multi-signer signing engine.
 *
 * <p>Drives one signer at a time through the full pipeline:</p>
 *
 * <pre>
 * prepare   : signers become READY in order
 * authenticate: token login for the current signer (PIN handled by caller)
 * sign      : incremental revision signed by the current signer's certificate
 * verify    : the WHOLE signature chain is re-verified; a signer whose
 *             signature invalidates an earlier one stops the workflow
 * </pre>
 *
 * <p>The engine holds no token state of its own: it drives the singleton
 * {@link TokenService} through its state machine (logout/disconnect between
 * signers so the next signer's token can be detected), delegates signature
 * creation to {@link PdfSigningService} (already incremental and chain-safe
 * via unique signature field names) and verification to the chain-aware
 * {@link PdfVerificationService}. Because every step is a method call on an
 * ordered signer list, a future parallel scheduler can reuse the engine
 * unchanged.</p>
 *
 * <p>Security: PINs are received as a {@code char[]}, forwarded to
 * {@link TokenService#authenticate(char[])} (which wipes them) and never
 * stored, logged or copied.</p>
 */
@Service
public class WorkflowSigningEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSigningEngine.class);

    private final TokenService tokenService;
    private final PdfSigningService pdfSigningService;
    private final PdfVerificationService pdfVerificationService;

    public WorkflowSigningEngine(TokenService tokenService,
                                 PdfSigningService pdfSigningService,
                                 PdfVerificationService pdfVerificationService) {
        this.tokenService = tokenService;
        this.pdfSigningService = pdfSigningService;
        this.pdfVerificationService = pdfVerificationService;
    }

    /** Marks the workflow started (signer 1 READY). */
    public void start(SigningWorkflow workflow) {
        workflow.start();
    }

    /**
     * Prepares the current signer's turn: releases any previously connected
     * token so the next signer's device can be detected.
     *
     * <p>A completed workflow has no current signer; this is then a harmless
     * no-op (the dialog must still release the token itself).</p>
     */
    public void prepareCurrentSigner(SigningWorkflow workflow) {
        Signer signer = workflow.getCurrentSigner();
        if (signer == null) {
            log.debug("Workflow complete — nothing to prepare");
            return;
        }
        releaseTokenForNextSigner();
        workflow.updateSignerStatus(signer.getOrder(), SigningStatus.READY);
        log.info("Prepared signer {} ({})", signer.getOrder(), signer.getDisplayName());
    }

    /**
     * Assigns the certificate the current signer will sign with.
     *
     * @return the updated signer
     */
    public Signer assignCertificate(SigningWorkflow workflow, String keyAlias) {
        Signer signer = requireCurrent(workflow);
        var certificate = tokenService.getCertificates().stream()
                .filter(c -> c.getAlias().equals(keyAlias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No certificate for alias: " + keyAlias));
        signer.setCertificate(certificate);
        log.info("Signer {} assigned certificate {} ({})", signer.getOrder(),
                keyAlias, certificate.getSubject());
        return signer;
    }

    /**
     * Authenticates the current signer with their token PIN. The PIN array is
     * wiped as soon as {@link TokenService} has consumed it; this method never
     * retains or logs it.
     */
    public void authenticate(SigningWorkflow workflow, char[] pin) {
        Signer signer = requireCurrent(workflow);
        Objects.requireNonNull(pin, "pin must not be null");
        if (signer.getKeyAlias() == null) {
            throw new IllegalStateException("Signer " + signer.getOrder()
                    + " has no certificate assigned yet");
        }
        workflow.updateSignerStatus(signer.getOrder(), SigningStatus.AUTHENTICATING);
        try {
            if (tokenService.getState() == TokenState.CERTIFICATE_AVAILABLE) {
                tokenService.startAuthentication();
            }
            tokenService.authenticate(pin); // TokenService wipes the PIN
            workflow.updateSignerStatus(signer.getOrder(), SigningStatus.SIGNING);
            log.info("Signer {} authenticated", signer.getOrder());
        } catch (RuntimeException e) {
            workflow.updateSignerStatus(signer.getOrder(), SigningStatus.FAILED);
            throw e;
        }
    }

    /**
     * Signs the document with the current signer and verifies the full chain.
     *
     * @param inputPdf  the PDF as of the previous revision (original or the
     *                  output of the previous signer)
     * @param placement visible signature placement, or {@code null}
     * @param outputPdf destination for this signer's revision
     * @return the per-signer result: signer, revision path and chain verdict
     */
    public SignerRevision sign(SigningWorkflow workflow,
                               Path inputPdf,
                               SignaturePlacement placement,
                               Path outputPdf) throws Exception {
        return sign(workflow, inputPdf, placement, outputPdf, null);
    }

    /**
     * As above, with explicit control over the visible appearance and the
     * signature dictionary's {@code /Reason} and {@code /Location}.
     */
    public SignerRevision sign(SigningWorkflow workflow,
                               Path inputPdf,
                               SignaturePlacement placement,
                               Path outputPdf,
                               com.example.signer.signing.SignatureAppearance appearance)
            throws Exception {
        Signer signer = requireCurrent(workflow);
        if (!signer.isReadyToSign() && signer.getStatus() != SigningStatus.SIGNING) {
            throw new IllegalStateException("Signer " + signer.getOrder()
                    + " is not ready to sign (" + signer.getStatus() + ")");
        }
        SigningAlgorithm algorithm = algorithmFor(signer);
        log.info("Signer {} signing revision {} -> {}", signer.getOrder(),
                inputPdf.getFileName(), outputPdf.getFileName());
        // The visible stamp's name line carries the signer's typed display
        // name (falling back to the certificate CN when they left it blank);
        // the certificate line keeps the real CN either way.
        com.example.signer.signing.SignatureAppearance effectiveAppearance =
                appearance == null ? null
                        : appearance.withSignerName(signer.getDisplayName());
        SigningResult result = pdfSigningService.signPdf(
                inputPdf, signer.getKeyAlias(), algorithm, outputPdf, placement,
                effectiveAppearance);
        if (Files.size(outputPdf) == 0) {
            throw new IllegalStateException("Signing produced an empty output file");
        }

        // The chain verdict covers every signer so far: signer n+1 must never
        // invalidate signer n, and this check is what guarantees it.
        SignatureVerificationResult verification =
                pdfVerificationService.verifyPdf(outputPdf);

        SignerRevision revision = new SignerRevision(
                signer.getOrder(), signer.getDisplayName(), result.signerSubject(),
                algorithm, outputPdf, verification);
        if (!verification.valid()) {
            workflow.updateSignerStatus(signer.getOrder(), SigningStatus.FAILED);
            log.warn("Signer {} produced an invalid chain: {}", signer.getOrder(),
                    verification.message());
            throw new ChainVerificationException(revision);
        }
        workflow.updateSignerStatus(signer.getOrder(), SigningStatus.SIGNED);
        log.info("Signer {} signed and chain verified ({} signature(s) valid)",
                signer.getOrder(), verification.signatures().size());
        return revision;
    }

    /** Per-signer outcome of one signing round. */
    public record SignerRevision(
            int order,
            String displayName,
            String signerSubject,
            SigningAlgorithm algorithm,
            Path revisionPdf,
            SignatureVerificationResult verification) {
    }

    /** Thrown when a new signature breaks the existing chain. */
    public static class ChainVerificationException extends Exception {
        private final transient SignerRevision revision;

        public ChainVerificationException(SignerRevision revision) {
            super("Signature verification failed: " + revision.verification().message());
            this.revision = revision;
        }

        public SignerRevision getRevision() {
            return revision;
        }
    }

    /** The currently active signer; fails when the workflow is complete. */
    private Signer requireCurrent(SigningWorkflow workflow) {
        Signer signer = workflow.getCurrentSigner();
        if (signer == null) {
            throw new IllegalStateException("Workflow is already complete");
        }
        return signer;
    }

    /**
     * Frees the singleton token slot between signers: logs out, disconnects
     * and re-scans. Harmless when no token was ever connected.
     */
    private void releaseTokenForNextSigner() {
        TokenState state = tokenService.getState();
        if (state == TokenState.AUTHENTICATED
                || state == TokenState.SIGNATURE_COMPLETE
                || state == TokenState.SIGNATURE_FAILED) {
            tokenService.logout();
            state = tokenService.getState();
        }
        if (state != TokenState.NO_TOKEN) {
            tokenService.disconnect();
        }
    }

    /** Picks the algorithm matching the signer certificate's key type. */
    private static SigningAlgorithm algorithmFor(Signer signer) {
        String keyAlgorithm = signer.getCertificate()
                .map(c -> c.getPublicKeyAlgorithm())
                .orElse("RSA");
        return "EC".equals(keyAlgorithm)
                ? SigningAlgorithm.ECDSA_SHA256
                : SigningAlgorithm.RSA_SHA256;
    }

    /** Convenience: the revisions of a workflow executed so far, in order. */
    public static List<Integer> signedOrders(SigningWorkflow workflow) {
        List<Integer> orders = new ArrayList<>();
        workflow.getSigners().stream()
                .filter(s -> s.getStatus() == SigningStatus.SIGNED)
                .forEach(s -> orders.add(s.getOrder()));
        return List.copyOf(orders);
    }
}

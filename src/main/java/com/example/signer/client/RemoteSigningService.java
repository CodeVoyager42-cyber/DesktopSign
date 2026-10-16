package com.example.signer.client;

import com.example.signer.signing.SignaturePlacement;
import com.example.signer.workflow.CertificateIdentity;
import com.example.signer.workflow.WorkflowSignerStep;
import java.util.ArrayList;
import java.util.Arrays;
import com.example.signer.exception.ErrorCode;
import com.example.signer.exception.SignerException;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.token.TokenService;
import com.example.signer.token.mock.MockSigningToken;
import com.example.signer.workflow.WorkflowSigningService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Desktop-side orchestration of one REMOTE signing step: task → download →
 * local validation → LOCAL PKCS#11 signing through the EXISTING portable
 * workflow domain ({@code WorkflowSigningService.signNextStep}) → upload with
 * an idempotency key. No signing logic is duplicated for remote mode.
 *
 * <p>Security: the PIN is consumed by {@link TokenService} locally and never
 * appears here; the private key never leaves the token; only the resulting
 * signed PDF travels to the server.
 */
public class RemoteSigningService {

    private static final Logger log = LoggerFactory.getLogger(RemoteSigningService.class);

    private final SignerTasksApi api;
    private final WorkflowSigningService signingService;
    private final TokenService tokenService;

    public RemoteSigningService(SignerTasksApi api,
                                WorkflowSigningService signingService,
                                TokenService tokenService) {
        this.api = api;
        this.signingService = signingService;
        this.tokenService = tokenService;
    }

    /** Workflows waiting for this user's signature. */
    public List<WorkflowView> myTasks() {
        return api.myTasks();
    }

    /** The workflow's current task metadata for the UI. */
    public TaskView task(String workflowId) {
        return api.task(workflowId);
    }

    /** Revision and audit history for the UI. */
    public List<Object> history(String workflowId) {
        return List.copyOf(api.revisions(workflowId));
    }

    /**
     * One full remote signing step. Runs on a worker thread (the caller owns
     * threading); every failure throws {@link SignerException} with a
     * user-safe message.
     *
     * @param workflowId the workflow to sign
     * @param tokenPin   the LOCAL token PIN (never leaves this machine)
     * @param placement  the signer's chosen visible-signature geometry in
     *                   PDF points (zero-based page, origin bottom-left), or
     *                   {@code null} to sign invisibly
     * @param workDir    directory for the downloaded and signed revisions
     * @return server response describing the accepted revision
     */
    public SignatureSubmissionResponse signRemoteStep(String workflowId,
                                                      char[] tokenPin,
                                                      SignaturePlacement placement,
                                                      Path workDir) {
        return signRemoteStep(workflowId, tokenPin, (pdf, order) -> placement, workDir);
    }

    /**
     * Resolves the visible-signature box against the downloaded revision
     * (page count, page size, signer order) — used by the plain-language
     * {@link SignaturePosition} picker.
     */
    public SignatureSubmissionResponse signRemoteStep(String workflowId,
                                                      char[] tokenPin,
                                                      SignaturePosition position,
                                                      Path workDir) {
        SignaturePosition chosen = position == null ? SignaturePosition.INVISIBLE : position;
        return signRemoteStep(workflowId, tokenPin, chosen::resolve, workDir);
    }

    /**
     * What the review and confirm screens show before signing: the exact
     * current revision (downloaded to {@code workDir}), its local
     * verification, and the certificate the workflow expects for this
     * signer. Read-only: nothing is signed or uploaded. The signing step
     * downloads and checks the document again, so a revision that changes
     * in between is caught there.
     */
    public record ReviewPackage(TaskView task, Path pdf,
                                WorkflowSigningService.WorkflowDocument document,
                                CertificateIdentity requiredCertificate,
                                WorkflowSignerStep step,
                                List<SignaturePlacement> otherPlacements) {
    }

    public ReviewPackage prepareReview(String workflowId, Path workDir) {
        try {
            TaskView task = api.task(workflowId);
            if (task.waitingForClaim()) {
                task = api.claimTurn(workflowId);
            }
            byte[] pdf = api.document(workflowId);
            Path downloaded = workDir.resolve(workflowId + "-review-rev"
                    + task.documentRevision() + ".pdf");
            Files.write(downloaded, pdf);
            WorkflowSigningService.WorkflowDocument doc = signingService.load(downloaded);
            WorkflowSignerStep mine = null;
            List<SignaturePlacement> others = new ArrayList<>();
            if (doc.manifest() != null) {
                for (WorkflowSignerStep step
                        : doc.manifest().stepsInOrder()) {
                    if (task.signerOrder() != null && step.order() == task.signerOrder()) {
                        mine = step;
                    } else if (step.placement() != null && step.signedAt() != null) {
                        // Boxes of signatures already in the document, so the
                        // signer can avoid covering them.
                        others.add(step.placement());
                    }
                }
            }
            return new ReviewPackage(task, downloaded, doc,
                    mine == null ? null : mine.certificate(), mine, List.copyOf(others));
        } catch (IOException e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The document could not be saved on this computer for review.", e);
        }
    }

    /** Computes the placement once the exact revision has been downloaded. */
    @FunctionalInterface
    public interface PlacementResolver {
        SignaturePlacement resolve(Path downloadedPdf,
                                                              Integer signerOrder);
    }

    /** The visible stages of one remote signing step, in order. */
    public enum Stage {
        DOWNLOAD("Downloading the document"),
        VALIDATE("Checking the document and its existing signatures"),
        UNLOCK_TOKEN("Unlocking your signing token"),
        SIGN("Creating and verifying your digital signature"),
        SUBMIT("Sending the signed document to the SignFlow server"),
        DONE("Signature accepted by the server");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Progress callbacks (called on the worker thread). */
    public interface Listener {
        Listener NONE = new Listener() {
        };

        /** {@code stage} has started. */
        default void stage(Stage stage) {
        }

        /** The locally signed file (before upload), for "View signed document". */
        default void signedFile(Path signedPdf) {
        }
    }

    public SignatureSubmissionResponse signRemoteStep(String workflowId,
                                                      char[] tokenPin,
                                                      PlacementResolver placementResolver,
                                                      Path workDir) {
        return signRemoteStep(workflowId, tokenPin, placementResolver, workDir, Listener.NONE);
    }

    public SignatureSubmissionResponse signRemoteStep(String workflowId,
                                                      char[] tokenPin,
                                                      SignaturePosition position,
                                                      Path workDir,
                                                      Listener listener) {
        SignaturePosition chosen = position == null ? SignaturePosition.INVISIBLE : position;
        return signRemoteStep(workflowId, tokenPin, chosen::resolve, workDir, listener);
    }

    public SignatureSubmissionResponse signRemoteStep(String workflowId,
                                                      char[] tokenPin,
                                                      PlacementResolver placementResolver,
                                                      Path workDir,
                                                      Listener listener) {
        return signRemoteStep(workflowId, tokenPin, placementResolver, null, null,
                workDir, listener);
    }

    /**
     * Full form used by the signing workspace.
     *
     * @param appearance       reason / location chosen by the signer (stored in
     *                         the signature and shown in its stamp); null uses
     *                         the default reason and no location
     * @param reviewedRevision the revision the signer reviewed and placed the
     *                         box on; when the server's current revision is
     *                         different the step stops BEFORE the token is
     *                         touched (the box could land on the wrong page).
     *                         Null skips the check.
     */
    public SignatureSubmissionResponse signRemoteStep(String workflowId,
                                                      char[] tokenPin,
                                                      PlacementResolver placementResolver,
                                                      WorkflowSigningService.SignatureAppearanceHint appearance,
                                                      Integer reviewedRevision,
                                                      Path workDir,
                                                      Listener listener) {
        Listener progress = listener == null ? Listener.NONE : listener;
        try {
            progress.stage(Stage.DOWNLOAD);
            // 1. Task metadata: which revision does the server expect?
            TaskView task = api.task(workflowId);

            // 1b. FLEXIBLE workflows: claim the turn BEFORE downloading —
            // the server rejects the download while another signer holds the
            // turn, so claiming first turns a late race into a clean typed
            // error instead of a wasted download. Idempotent for own claims.
            if (task.waitingForClaim()) {
                task = api.claimTurn(workflowId);
            }
            if (reviewedRevision != null && task.documentRevision() != reviewedRevision) {
                Arrays.fill(tokenPin, '\0');
                throw new SignerException(ErrorCode.WORKFLOW_STALE_DOCUMENT,
                        "The document changed after you reviewed it. Review the new"
                                + " version and place your signature again.");
            }

            // 2. Download the exact current revision.
            byte[] pdf = api.document(workflowId);
            Path downloaded = workDir.resolve(workflowId + "-rev"
                    + task.documentRevision() + ".pdf");
            Files.write(downloaded, pdf);

            SignaturePlacement placement =
                    placementResolver.resolve(downloaded, task.signerOrder());

            // 3. Local pre-sign validation: same verifier, same rules.
            progress.stage(Stage.VALIDATE);
            WorkflowSigningService.WorkflowDocument doc = signingService.load(downloaded);
            if (doc.parseFailure() != null) {
                throw doc.parseFailure();
            }
            if (doc.effective() == null) {
                throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                        "The downloaded document does not carry the workflow manifest");
            }
            if (doc.verification() != null && doc.verification().signatures() != null) {
                doc.verification().signatures().forEach(sig -> {
                    if (!sig.valid()) {
                        throw new SignerException(ErrorCode.SIGNATURE_VERIFICATION_FAILED,
                                "An existing signature is invalid: " + sig.message());
                    }
                });
            }

            // 4. LOCAL PKCS#11 signing via the existing domain path (which
            //    itself re-validates turn, chain and certificate match, then
            //    signs incrementally and re-verifies the result).
            progress.stage(Stage.UNLOCK_TOKEN);
            tokenService.detect();
            tokenService.startAuthentication();
            try {
                tokenService.authenticate(tokenPin);
            } finally {
                Arrays.fill(tokenPin, '\0');
            }

            String signingOperationId = UUID.randomUUID().toString();
            Path signedOutput = workDir.resolve(workflowId + "-rev"
                    + (task.documentRevision() + 1) + ".pdf");
            // The task names the claimed signer — on a token that carries
            // certificates for several pending signers, it disambiguates
            // which workflow step this signature fulfils. The placement hint
            // is stamped into the embedded manifest so the server can record
            // where the signature visually appears (brief §33).
            progress.stage(Stage.SIGN);
            signingService.signNextStep(downloaded, appearance, signedOutput,
                    task.signerOrder(), placement);
            progress.signedFile(signedOutput);

            // 5. Upload: parent = the revision we downloaded, idempotency key
            //    ties retries to this one logical operation.
            progress.stage(Stage.SUBMIT);
            byte[] signedBytes = Files.readAllBytes(signedOutput);
            SignatureSubmissionResponse response = submitIdempotently(api, workflowId,
                    new SignatureSubmissionRequest(task.documentRevision(),
                            task.documentSha256(), signingOperationId),
                    signedBytes, SUBMIT_ATTEMPTS, RETRY_BACKOFF_MILLIS);
            log.info("Remote step submitted for {} (revision {} accepted)",
                    workflowId, response.acceptedRevision());
            progress.stage(Stage.DONE);
            return response;
        } catch (IOException e) {
            throw new SignerException(ErrorCode.WORKFLOW_INVALID,
                    "The remote signing step failed locally", e);
        }
    }

    /** Upload attempts for one logical submission (first try + retries). */
    static final int SUBMIT_ATTEMPTS = 3;
    static final long RETRY_BACKOFF_MILLIS = 1_000L;

    /**
     * Uploads one signed revision with idempotent retry (P0.8).
     *
     * <p>The SAME {@code signingOperationId} and the SAME bytes are sent on
     * every attempt: when the first upload was accepted but the response was
     * lost (timeout, dropped connection), the server recognises the operation
     * id of the caller's already-signed slot and answers with the ORIGINAL
     * accepted revision instead of creating a second one. Only transport
     * failures — no HTTP response at all — are retried; any answer from the
     * server (accepted, conflict, validation error) is final.
     */
    static SignatureSubmissionResponse submitIdempotently(SignerTasksApi api,
                                                          String workflowId,
                                                          SignatureSubmissionRequest request,
                                                          byte[] signedBytes,
                                                          int attempts,
                                                          long backoffMillis) {
        SignerException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return api.submitSignature(workflowId, request, signedBytes);
            } catch (SignerException e) {
                if (!WorkflowApiClient.isTransportFailure(e)) {
                    throw e;
                }
                last = e;
                log.warn("Signature upload for {} got no response (attempt {}/{}):"
                                + " retrying with the same operation id",
                        workflowId, attempt, attempts);
                if (attempt < attempts && backoffMillis > 0) {
                    try {
                        Thread.sleep(backoffMillis * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }
}

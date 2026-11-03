package com.example.signer.server.workflow;

import com.example.signer.server.RemoteServerProperties;
import com.example.signer.server.auth.UserAccountService;
import org.springframework.beans.factory.ObjectProvider;
import com.example.signer.server.api.ApiStates.ParticipantState;

import com.example.signer.server.api.ApiContracts.AuditView;
import com.example.signer.server.api.ApiContracts.BindCertificateRequest;
import com.example.signer.server.api.ApiContracts.CreateWorkflowRequest;
import com.example.signer.server.api.ApiContracts.RevisionView;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionRequest;
import com.example.signer.server.api.ApiContracts.SignatureSubmissionResponse;
import com.example.signer.server.api.ApiContracts.SigningTasksView;
import com.example.signer.server.api.ApiContracts.TaskItemView;
import com.example.signer.server.api.ApiContracts.ParticipantStateView;
import com.example.signer.server.api.ApiContracts.TaskView;
import com.example.signer.server.api.ApiContracts.WorkflowView;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;

import com.example.signer.exception.SignerException;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.token.DevelopmentSigningIdentities;
import com.example.signer.verification.PdfVerificationService;
import com.example.signer.verification.SignatureVerificationResult;
import com.example.signer.workflow.CertificateIdentity;
import com.example.signer.workflow.WorkflowManifest;
import com.example.signer.workflow.WorkflowSignerStep;
import com.example.signer.workflow.WorkflowSigningService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side coordination of REMOTE signing workflows.
 *
 * <p><b>One document, one signer.</b> A workflow is created by the account
 * that uploads the PDF, and that account is its only signer. Nobody is
 * invited, nothing is sent to anybody, and no turn is ever handed over: the
 * creator binds their own certificate, downloads the prepared revision,
 * signs it on their own machine and submits it, which completes the workflow.
 *
 * <p>Trust model: the PDF signature chain is the only cryptographic truth;
 * workflow state is server-owned and never taken from client claims. A
 * submission is accepted only when
 *
 * <ul>
 *   <li>the authenticated user IS the workflow's signer,</li>
 *   <li>the workflow still expects the submitted parent revision (number AND
 *       SHA-256),</li>
 *   <li>the submitted PDF is a pure incremental update: byte-identical to the
 *       stored parent for its whole prefix,</li>
 *   <li>the full signature chain — including the new signature — verifies
 *       through the SAME verifier the desktop uses, and</li>
 *   <li>the NEW signature's certificate fingerprint matches the identity
 *       bound to the signer slot.</li>
 * </ul>
 *
 * <p>Submissions are idempotent by {@code signingOperationId}: a network
 * timeout followed by a retry returns the original accepted result without a
 * second transition. Every decision is audited.
 */
@Service
public class RemoteWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(RemoteWorkflowService.class);

    private final WorkflowStore store;
    private final RemoteServerProperties properties;
    private final PdfVerificationService pdfVerification;
    private final WorkflowSigningService workflowSigningService;
    private final SignaturePlacementExtractor placementExtractor;

    /**
     * Account existence check for workflow validation: the YAML-config
     * accounts in file mode, the users table in database mode.
     */
    private final UserAccountService accounts;

    /** Human directory (database mode): display names. */
    private final WorkflowDirectory directory;

    private final AtomicLong workflowSeq = new AtomicLong(System.currentTimeMillis());

    public RemoteWorkflowService(WorkflowStore store,
                                 RemoteServerProperties properties,
                                 PdfVerificationService pdfVerification,
                                 WorkflowSigningService workflowSigningService,
                                 SignaturePlacementExtractor placementExtractor,
                                 ObjectProvider<UserAccountService> accounts,
                                 ObjectProvider<WorkflowDirectory> directory) {
        this.store = store;
        this.properties = properties;
        this.pdfVerification = pdfVerification;
        this.workflowSigningService = workflowSigningService;
        this.placementExtractor = placementExtractor;
        this.accounts = accounts.getIfAvailable();
        this.directory = directory.getIfAvailable();
    }

    /* ------------------------------------------------------------------
     * Creation: revision 0 = manifest-embedded original (reuses the
     * existing portable-workflow domain — no signing logic duplicated)
     * ------------------------------------------------------------------ */

    /**
     * Creates a workflow from an uploaded original PDF, to be signed by
     * {@code creator} — the only signer there is.
     *
     * <p>When the creator already arrives with a certificate fingerprint, the
     * server immediately embeds the portable manifest and stores revision 0.
     * Otherwise the workflow stays in DRAFT with NO stored document: the
     * signer binds their own certificate from the machine their token is
     * plugged into ({@link #bindCertificate}), and the manifest-bearing
     * revision 0 is prepared once the slot is bound. Revision 0 is
     * immutable, so binding must complete before it exists.
     */
    public WorkflowView createWorkflow(CreateWorkflowRequest request,
                                       byte[] originalPdf,
                                       String creator) {
        // Phase D decision: an account with an unverified e-mail address may
        // sign in, but may not put its name on a document. File-mode accounts
        // have no address to verify, so the seam answers true there.
        if (!accounts.maySign(creator)) {
            throw new ApiException(ApiErrorCode.EMAIL_NOT_VERIFIED,
                    "Verify your e-mail address before signing a document");
        }
        validate(request, creator);
        String workflowId = "WF-" + workflowSeq.incrementAndGet();

        SigningWorkflowAggregate aggregate = SigningWorkflowAggregate.create(
                workflowId, request.documentName(), mapSigner(request, creator), creator);
        aggregate.appendAudit(AuditEventType.WORKFLOW_CREATED, creator,
                "Document uploaded for signing");

        synchronized (store.lockFor(workflowId)) {
            if (!aggregate.allBound()) {
                // Its own event type, not a second WORKFLOW_CREATED: this is a
                // different fact about the workflow, and a reader that shows
                // the type rather than this prose could only say "created"
                // twice (the website's status timeline does exactly that).
                aggregate.appendAudit(AuditEventType.CERTIFICATE_PENDING, creator,
                        "A certificate must be registered before the document"
                                + " is prepared");
                store.stageOriginal(workflowId, originalPdf);
                store.save(aggregate);
                log.info("Workflow {} created by {} — DRAFT, certificate pending",
                        workflowId, creator);
                return toView(aggregate);
            }
            prepareWorkflow(aggregate, originalPdf, creator);
            return toView(aggregate);
        }
    }

    /**
     * Binds the authenticated signer's certificate to their UNBOUND slot
     * (certificate enrollment happens on the signer's own machine). Only the
     * slot's own user may bind it, only while the workflow is DRAFT. Binding
     * prepares the document (revision 0) atomically.
     */
    public WorkflowView bindCertificate(String workflowId, String userId,
                                        BindCertificateRequest body) {
        String fingerprint = body == null || body.certificateSha256() == null
                ? "" : body.certificateSha256().replace(":", "").trim();
        if (!fingerprint.matches("[0-9a-fA-F]{64}")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "The certificate fingerprint must be 64 hex characters (the"
                            + " SHA-256 of the signer certificate, captured from"
                            + " your USB token)");
        }
        rejectDevelopmentFingerprint(fingerprint, "signer " + userId);
        synchronized (store.lockFor(workflowId)) {
            // Mutation protocol: load INSIDE the lock, validate the fresh copy.
            SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
            if (wf.status() != WorkflowState.DRAFT) {
                throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "Certificates can only be bound while the workflow is"
                                + " awaiting enrollment (status " + wf.status() + ")");
            }
            WorkflowSignerRecord slot = wf.signer();
            if (!slot.userId().equals(userId)) {
                throw new ApiException(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE,
                        "You are not the signer of this workflow");
            }
            if (slot.signed()) {
                throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "Your signature is already recorded");
            }
            // Security Phase 3: a slot's certificate is set once and never
            // replaced.
            if (slot.isBound()) {
                throw new ApiException(ApiErrorCode.CERTIFICATE_ALREADY_BOUND,
                        "A certificate is already assigned to your signer slot");
            }
            wf.bindCertificate(userId, fingerprint.toUpperCase(Locale.ROOT));
            wf.appendAudit(AuditEventType.CERTIFICATE_BOUND, userId,
                    "Certificate bound (" + shortFingerprint(fingerprint) + ")");
            byte[] original = store.readStagedOriginal(workflowId);
            if (original == null) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                        "The staged original document is missing — the"
                                + " workflow cannot be prepared");
            }
            prepareWorkflow(wf, original, wf.createdBy());
            log.info("Workflow {}: certificate bound by {} — document prepared",
                    workflowId, userId);
            return toView(wf);
        }
    }

    /**
     * Embeds the manifest into the original, stores immutable revision 0 and
     * activates the signing turn. Caller must hold the workflow lock.
     */
    private void prepareWorkflow(SigningWorkflowAggregate wf, byte[] originalPdf,
                                 String actor) {
        WorkflowSignerRecord s = wf.signer();
        CertificateIdentity identity = s.isBound()
                ? new CertificateIdentity(s.certificateSha256(), "", "", "")
                : CertificateIdentity.unbound();
        List<WorkflowSignerStep> steps = List.of(
                WorkflowSignerStep.of(s.order(), "Signer " + s.order(), identity, null));
        Path original = null;
        Path prepared = null;
        try {
            original = Files.createTempFile("wf-create-", ".pdf");
            Files.write(original, originalPdf);
            WorkflowManifest manifest = workflowSigningService.createWorkflow(
                    original, wf.documentName(), steps);
            prepared = Files.createTempFile("wf-prepared-", ".pdf");
            workflowSigningService.writeManifestIntoCopy(original, manifest, prepared);
            byte[] revision0 = Files.readAllBytes(prepared);

            wf.replaceRevision0(new DocumentRevisionRecord(0, -1,
                    WorkflowFileStore.sha256Hex(revision0), revision0.length,
                    Instant.now(), actor));
            store.writeRevision(wf.workflowId(), 0, revision0);
            try {
                wf.transition(WorkflowState.READY_FOR_SIGNING);
                wf.transition(WorkflowState.WAITING_FOR_SIGNER);
                wf.appendAudit(AuditEventType.WORKFLOW_PREPARED, actor,
                        "Revision 0 stored (" + revision0.length + " bytes) —"
                                + " ready to sign");
                store.save(wf);
            } catch (RuntimeException e) {
                // Not committed: never leave an orphan revision 0 behind.
                try {
                    store.discardUncommittedRevision(wf.workflowId(), 0);
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                    "The workflow could not be prepared", e);
        } catch (SignerException e) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, e.getMessage(), e);
        } finally {
            deleteQuietly(original);
            deleteQuietly(prepared);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    /* ------------------------------------------------------------------
     * Retrieval
     * ------------------------------------------------------------------ */

    /** The workflow view for its signer. */
    public WorkflowView getWorkflow(String workflowId, String userId) {
        return toView(requireAccessible(workflowId, userId));
    }

    /** The authenticated user's signing task in {@code workflowId}. */
    public TaskView getTask(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        WorkflowSignerRecord signer = wf.currentSigner();
        WorkflowSignerRecord slot = wf.signer();
        TaskView.StepView step = new TaskView.StepView(slot.order(), slot.userId(),
                slot.signed(), signer != null, slot.isBound(),
                slot.signed() ? "SIGNED" : "PENDING");
        log.info("Signing task opened for workflow {} by {} (status {}, signed={})",
                wf.workflowId(), userId, wf.status(), slot.signed());
        return new TaskView(wf.workflowId(), wf.documentName(), wf.status().name(),
                signer == null ? null : signer.order(),
                signer == null ? null : signatureFieldFor(signer.order()),
                wf.currentRevision().revisionNumber(), wf.currentRevision().sha256(),
                List.of(step));
    }

    /**
     * The signer slot {@code userId} may open in the desktop right now, or a
     * refusal. Checked when a launch code is issued AND again when it is
     * exchanged. Allowed:
     * <ul>
     *   <li>DRAFT, the slot has no certificate yet (enrolment);</li>
     *   <li>WAITING_FOR_SIGNER, the slot is unsigned.</li>
     * </ul>
     * Anybody else gets the uniform "not found".
     *
     * @return the user's signer order
     */
    public int requireLaunchableSlot(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        WorkflowSignerRecord slot = wf.signer();
        if (!slot.userId().equals(userId)) {
            throw new ApiException(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE,
                    "Only the signer of this workflow can open it for signing");
        }
        if (slot.signed()) {
            throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                    "Your signature is already recorded");
        }
        // Remediation of H2: a launch never registers a certificate. The
        // slot must already have one, registered through the signed-in
        // desktop, so a stolen launch code cannot attach another card to
        // this signer's slot.
        if (!slot.isBound()) {
            throw new ApiException(ApiErrorCode.CERTIFICATE_NOT_BOUND,
                    "Your smart card is not registered for this signing task yet."
                            + " Open the task in SignFlow while signed in and connect your"
                            + " card first.");
        }
        if (wf.status() != WorkflowState.WAITING_FOR_SIGNER) {
            throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                    "The workflow is " + wf.status() + " and cannot be signed");
        }
        return slot.order();
    }

    /** Where a desktop task session's slot stands (Phase B remediation). */
    public enum TaskSlotState {
        /** Slot belongs to the account, is bound and unsigned, workflow active. */
        ACTIVE,
        /** Same slot, but signed already or the workflow is cancelled/completed/failed. */
        FINISHED,
        /** The slot no longer matches the session (or the workflow is gone). */
        INVALID
    }

    /**
     * State of slot {@code signerOrder} of {@code workflowId} for a task
     * session of {@code userId}; evaluated on every request of the session.
     */
    public TaskSlotState taskSlotState(String workflowId, String userId, int signerOrder) {
        SigningWorkflowAggregate wf;
        try {
            wf = requireAccessible(workflowId, userId);
        } catch (ApiException e) {
            return TaskSlotState.INVALID;
        }
        WorkflowSignerRecord slot = wf.signer();
        if (slot.order() != signerOrder || !slot.userId().equals(userId)
                || !slot.isBound()) {
            return TaskSlotState.INVALID;
        }
        boolean active = switch (wf.status()) {
            case READY_FOR_SIGNING, WAITING_FOR_SIGNER, SIGNATURE_SUBMITTED, VALIDATING -> true;
            default -> false;
        };
        return active && !slot.signed() ? TaskSlotState.ACTIVE : TaskSlotState.FINISHED;
    }

    /**
     * The current revision bytes — only for the workflow's own signer.
     *
     * <p>A DRAFT workflow has no revision 0 yet: the signer's certificate is
     * not registered, so the manifest-bearing document cannot have been
     * prepared ({@link #createWorkflow}). That is a state the caller can act
     * on — register the certificate — so it answers
     * {@code CERTIFICATE_NOT_BOUND} (409), the same code the launch route
     * gives for the same situation, rather than failing on the missing file.
     */
    public byte[] getDocument(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        WorkflowSignerRecord signer = wf.currentSigner();
        if (signer == null || !signer.userId().equals(userId)) {
            throw new ApiException(ApiErrorCode.NOT_YOUR_TURN,
                    "This document is not waiting for your signature");
        }
        if (wf.status() == WorkflowState.DRAFT || !signer.isBound()) {
            throw new ApiException(ApiErrorCode.CERTIFICATE_NOT_BOUND,
                    "Register your signing certificate first: the document is"
                            + " prepared once the certificate that will sign it"
                            + " is known");
        }
        return store.readRevision(workflowId, wf.currentRevision().revisionNumber());
    }

    /**
     * The finished document, once it is signed.
     *
     * <p>{@link #getDocument} is the signing download: the revision to put a
     * signature on. This is the other kind — the result, once there is one.
     * Nothing can follow it, so no signing rule applies.
     *
     * <p>Before the signature there is deliberately nothing to give out:
     * {@code INVALID_WORKFLOW_STATE} (409) says so, and the uniform 404 still
     * covers anyone the workflow does not belong to.
     */
    public byte[] getCompletedDocument(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        if (wf.status() != WorkflowState.COMPLETED) {
            throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                    "This document is not signed yet");
        }
        return store.readRevision(workflowId, wf.currentRevision().revisionNumber());
    }

    /** Revision history visible to the signer. */
    public List<RevisionView> getRevisions(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        return wf.revisions().stream()
                .map(r -> new RevisionView(r.revisionNumber(), r.parentRevision(),
                        r.sha256(), r.sizeBytes(), r.createdAt().toString(),
                        r.createdBy()))
                .toList();
    }

    /**
     * What happened, in order, to a workflow the caller owns. Since a
     * workflow has exactly one participant, who is also its creator, there is
     * nobody the trail could leak anything to.
     */
    public List<AuditView> getAudit(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        return wf.audit().stream()
                .map(a -> new AuditView(a.sequence(), a.eventType().name(),
                        a.actor(), a.detail(), a.occurredAt().toString()))
                .toList();
    }

    /** Workflows that are waiting for {@code userId} to act. */
    public List<WorkflowView> myTasks(String userId) {
        List<WorkflowView> result = new ArrayList<>();
        for (String workflowId : store.listWorkflowIdsForSigner(userId)) {
            SigningWorkflowAggregate wf = loadQuietly(workflowId);
            if (wf == null) {
                continue;
            }
            if (wf.status() == WorkflowState.WAITING_FOR_SIGNER && wf.maySubmit(userId)) {
                result.add(toView(wf));
            } else if (wf.status() == WorkflowState.DRAFT
                    && wf.signer().userId().equals(userId)
                    && !wf.signer().isBound() && !wf.signer().signed()) {
                // The user still owes a certificate binding — surface it.
                result.add(toView(wf));
            }
        }
        return result;
    }

    /**
     * Every workflow {@code userId} created. Since the creator is also the
     * signer, this is simply "my documents" — every lifecycle state,
     * cancelled included.
     */
    public List<WorkflowView> ownedBy(String userId) {
        List<WorkflowView> result = new ArrayList<>();
        for (String workflowId : store.listWorkflowIdsCreatedBy(userId)) {
            SigningWorkflowAggregate wf = loadQuietly(workflowId);
            if (wf == null) {
                continue;
            }
            if (userId != null && userId.equals(wf.createdBy())) {
                result.add(toView(wf));
            }
        }
        return result;
    }

    private SigningWorkflowAggregate loadQuietly(String workflowId) {
        try {
            return store.load(workflowId);
        } catch (RuntimeException e) {
            log.warn("Skipping unreadable workflow {}", workflowId);
            return null;
        }
    }

    /* ------------------------------------------------------------------
     * The signing-tasks workspace (all lifecycle states)
     * ------------------------------------------------------------------ */

    /**
     * Every workflow the signed-in user has, across all lifecycle states —
     * unlike {@link #myTasks}, which returns only actionable items. This is
     * the data behind the desktop "My documents" workspace and the website
     * dashboard.
     */
    public SigningTasksView listParticipating(String userId) {
        List<TaskItemView> tasks = new ArrayList<>();
        // Only the caller's workflows are loaded (one indexed query in
        // database mode).
        for (String workflowId : store.listWorkflowIdsForSigner(userId)) {
            SigningWorkflowAggregate wf = loadQuietly(workflowId);
            if (wf == null || !wf.signer().userId().equals(userId)) {
                continue;
            }
            tasks.add(toTaskItem(wf));
        }
        tasks.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                a.documentName(), b.documentName()));
        return new SigningTasksView(tasks);
    }

    /** Maps one aggregate to the task-item view. */
    private TaskItemView toTaskItem(SigningWorkflowAggregate wf) {
        WorkflowSignerRecord mine = wf.signer();
        String ownerId = wf.createdBy();
        WorkflowDirectory.Profile ownerProfile = ownerId == null || directory == null
                ? null : directory.profileOf(ownerId);

        // The slot's OWN signature first, then the workflow lifecycle.
        //
        // SIGNED is emitted ONLY when the slot carries an accepted signature
        // — never because the workflow is COMPLETED, never because the
        // document was opened.
        boolean slotSigned = mine.signed();
        ParticipantState label;
        boolean waitingForMe = false;
        if (slotSigned) {
            label = ParticipantState.SIGNED;
        } else if (wf.status() == WorkflowState.DRAFT && !mine.isBound()) {
            label = ParticipantState.CERTIFICATE_PENDING;
        } else if (wf.status() == WorkflowState.CANCELLED) {
            label = ParticipantState.CANCELLED;
        } else if (wf.status() == WorkflowState.FAILED) {
            label = ParticipantState.BLOCKED;
        } else if (wf.status() == WorkflowState.WAITING_FOR_SIGNER) {
            label = ParticipantState.YOUR_TURN;
            waitingForMe = true;
        } else {
            label = ParticipantState.IN_PROGRESS;
        }

        // Dates: created = the workflow's first audit event; completed = when
        // this slot's own signature was accepted.
        String createdAt = null;
        String completedAt = null;
        List<AuditEventRecord> audit = wf.audit();
        if (!audit.isEmpty()) {
            createdAt = audit.get(0).occurredAt().toString();
            if (slotSigned) {
                completedAt = mine.signedAt() != null
                        ? mine.signedAt().toString()
                        : audit.get(audit.size() - 1).occurredAt().toString();
            }
        }
        String updatedAt = audit.isEmpty() ? null
                : audit.get(audit.size() - 1).occurredAt().toString();
        return new TaskItemView(
                wf.workflowId(),
                wf.documentName(),
                ownerId,
                ownerProfile == null ? null : ownerProfile.displayName(),
                wf.status().name(),
                createdAt,
                completedAt,
                new ParticipantStateView(label, mine.order(), slotSigned, waitingForMe),
                updatedAt);
    }

    /* ------------------------------------------------------------------
     * Submission
     * ------------------------------------------------------------------ */

    /**
     * Validates and commits a signed revision. Idempotent by
     * {@code signingOperationId}. Concurrency: the whole read-validate-commit
     * cycle runs under the workflow's lock, and the parent revision check
     * rejects anything not built on the current revision.
     */
    public SignatureSubmissionResponse submitSignature(
            String workflowId, String userId, byte[] pdfBytes,
            SignatureSubmissionRequest body) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "Empty document upload");
        }
        if (body == null || body.signingOperationId() == null
                || body.signingOperationId().isBlank()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "signingOperationId is required");
        }
        synchronized (store.lockFor(workflowId)) {
            // Mutation protocol: the aggregate is loaded INSIDE the lock, so
            // every check below sees the latest committed state — a request
            // that raced another submission/cancel can never act on (or save)
            // a stale copy.
            SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);

            // ── idempotent replay (same caller, same operation id) ──
            WorkflowSignerRecord replayed = acceptedOperationOf(wf, userId,
                    body.signingOperationId());
            if (replayed != null) {
                wf.appendAudit(AuditEventType.DUPLICATE_OPERATION_IGNORED, userId,
                        "Replay of signing operation " + body.signingOperationId());
                store.save(wf);
                log.info("Duplicate submission ignored for {} ({})", workflowId,
                        body.signingOperationId());
                return replayResponse(wf, replayed);
            }

            // ── state and slot ──
            if (wf.status() != WorkflowState.WAITING_FOR_SIGNER) {
                throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "The workflow is not waiting for a signature (status "
                                + wf.status() + ")");
            }
            WorkflowSignerRecord current = wf.currentSigner();
            if (current == null || !current.userId().equals(userId)) {
                throw new ApiException(ApiErrorCode.NOT_YOUR_TURN,
                        "This document is not waiting for your signature");
            }

            // ── optimistic concurrency: parent revision number AND hash ──
            int expectedRevision = wf.currentRevision().revisionNumber();
            if (body.parentRevision() != expectedRevision) {
                rejectStale(wf, userId, "Submitted parent revision "
                        + body.parentRevision() + " but current is " + expectedRevision);
            }
            if (body.parentSha256() != null
                    && !body.parentSha256()
                            .equalsIgnoreCase(wf.currentRevision().sha256())) {
                rejectStale(wf, userId, "Parent hash mismatch for revision "
                        + expectedRevision);
            }

            // ── server-side PDF validation, independent of client claims ──
            validateSubmittedPdf(wf, userId, expectedRevision, pdfBytes, current);

            // ── commit: immutable revision file, then aggregate, then state ──
            int newRevision = expectedRevision + 1;
            store.writeRevision(workflowId, newRevision, pdfBytes);
            try {
                return commitAcceptedRevision(wf, userId, body, pdfBytes, current,
                        expectedRevision, newRevision);
            } catch (RuntimeException e) {
                // The state change was NOT committed: remove the revision file
                // again so it can never orphan the chain (an orphan N+1 would
                // make every later attempt fail with REVISION_CONFLICT).
                try {
                    store.discardUncommittedRevision(workflowId, newRevision);
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        }
    }

    /** Applies and persists an accepted, validated submission. Caller holds the lock. */
    private SignatureSubmissionResponse commitAcceptedRevision(
            SigningWorkflowAggregate wf, String userId, SignatureSubmissionRequest body,
            byte[] pdfBytes, WorkflowSignerRecord current, int expectedRevision,
            int newRevision) {
        String workflowId = wf.workflowId();
        wf.appendRevision(new DocumentRevisionRecord(newRevision,
                expectedRevision, WorkflowFileStore.sha256Hex(pdfBytes),
                pdfBytes.length, Instant.now(), userId));
        wf.appendAudit(AuditEventType.REVISION_CREATED, userId,
                "Revision " + newRevision + " accepted ("
                        + pdfBytes.length + " bytes)");
        // Durable placement metadata: read the accepted visible-signature
        // geometry out of the accepted revision's embedded manifest.
        // Metadata only — never a rejection reason.
        SignaturePlacement acceptedPlacement =
                placementExtractor.extract(workflowId, pdfBytes, expectedRevision);
        if (acceptedPlacement != null) {
            wf.appendAudit(AuditEventType.SIGNATURE_PLACEMENT_RECORDED, userId,
                    "Page " + (acceptedPlacement.pageIndex() + 1) + " ("
                            + (int) acceptedPlacement.x() + ", "
                            + (int) acceptedPlacement.y() + ")");
        }
        wf.markSignedByUser(userId, body.signingOperationId(), acceptedPlacement);
        wf.appendAudit(AuditEventType.SIGNER_COMPLETED, userId,
                current.userId() + " signed the document");
        wf.transition(WorkflowState.SIGNATURE_SUBMITTED);
        wf.transition(WorkflowState.VALIDATING);
        wf.transition(WorkflowState.COMPLETED);
        wf.appendAudit(AuditEventType.WORKFLOW_COMPLETED, userId,
                "The document is signed");
        store.save(wf);
        log.info("Workflow {}: revision {} accepted from {} — completed",
                workflowId, newRevision, userId);
        return currentResponse(wf);
    }

    /* ------------------------------------------------------------------
     * server-side validation of one submission
     * ------------------------------------------------------------------ */

    private void validateSubmittedPdf(SigningWorkflowAggregate wf, String userId,
                                      int parentRevision, byte[] pdfBytes,
                                      WorkflowSignerRecord current) {
        byte[] parentBytes = store.readRevision(wf.workflowId(), parentRevision);

        // 1. Pure incremental update: the submission must start with the
        //    parent's exact bytes (PDFBox saveIncremental preserves them),
        //    then append. Anything else is a document modification, not a
        //    signature — reject before any parsing.
        if (pdfBytes.length <= parentBytes.length
                || !Arrays.equals(Arrays.copyOf(pdfBytes, parentBytes.length),
                        parentBytes)) {
            wf.appendAudit(AuditEventType.SIGNATURE_VALIDATION_FAILED, userId,
                    "Submission is not an incremental update of revision "
                            + parentRevision);
            store.save(wf);
            throw new ApiException(ApiErrorCode.DOCUMENT_MODIFIED,
                    "The submitted document does not extend revision "
                            + parentRevision + " unmodified");
        }

        // 2. Chain verification through the SAME verifier the desktop uses:
        //    every signature crypto-valid, append-only containment, chain
        //    reaches EOF — i.e. nothing was appended after the new signature.
        Path temp = null;
        try {
            temp = Files.createTempFile("wf-submit-", ".pdf");
            Files.write(temp, pdfBytes);
            SignatureVerificationResult result;
            try {
                result = pdfVerification.verifyPdf(temp);
            } catch (Exception e) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                        "Signature validation could not run", e);
            }

            if (!result.valid()) {
                failValidation(wf, userId, ApiErrorCode.INVALID_SIGNATURE,
                        "Signature validation failed: " + result.message());
            }
            if (result.signatures().size() != parentRevision + 1) {
                failValidation(wf, userId, ApiErrorCode.VALIDATION_FAILED,
                        "Expected " + (parentRevision + 1) + " signatures, found "
                                + result.signatures().size());
            }

            // 3. Fingerprint binding: the NEW (last) signature must come from
            //    the certificate bound to this signer slot.
            SignatureVerificationResult.SignatureDetail newest =
                    result.signatures().get(result.signatures().size() - 1);
            String actualFp = normalizeFingerprint(newest.signerCertFingerprint());
            if (!properties.isAcceptDevelopmentIdentities()
                    && DevelopmentSigningIdentities.isDevelopmentIdentity(
                            newest.signerSubject(), actualFp)) {
                wf.appendAudit(AuditEventType.SIGNATURE_VALIDATION_FAILED, userId,
                        "Rejected a signature made with a development mock-token"
                                + " certificate");
                store.save(wf);
                throw new ApiException(ApiErrorCode.DEVELOPMENT_IDENTITY_REJECTED,
                        "This signature was created with the development mock"
                                + " token. Sign with your real USB token.");
            }
            String expectedFp = normalizeFingerprint(current.certificateSha256());
            if (actualFp == null || expectedFp == null || !actualFp.equals(expectedFp)) {
                wf.appendAudit(AuditEventType.CERTIFICATE_MISMATCH_REJECTED, userId,
                        "The new signature does not match the registered certificate");
                store.save(wf);
                throw new ApiException(ApiErrorCode.CERTIFICATE_MISMATCH,
                        "The signature was not created by the certificate "
                                + "registered for this document");
            }
            wf.appendAudit(AuditEventType.SIGNATURE_VALIDATED, userId,
                    "Signature validated");
        } catch (ApiException e) {
            throw e;
        } catch (IOException | SignerException e) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                    "Signature validation could not run", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private void rejectStale(SigningWorkflowAggregate wf, String userId, String detail) {
        wf.appendAudit(AuditEventType.STALE_REVISION_REJECTED, userId, detail);
        store.save(wf);
        throw new ApiException(ApiErrorCode.STALE_REVISION,
                "This signing document is no longer current. A newer workflow "
                        + "revision exists — reload the document and try again.");
    }

    private void failValidation(SigningWorkflowAggregate wf, String userId,
                                ApiErrorCode code, String message) {
        wf.appendAudit(AuditEventType.SIGNATURE_VALIDATION_FAILED, userId, message);
        store.save(wf);
        throw new ApiException(code, message);
    }

    /* ------------------------------------------------------------------
     * helpers
     * ------------------------------------------------------------------ */

    private SignatureSubmissionResponse currentResponse(SigningWorkflowAggregate wf) {
        return new SignatureSubmissionResponse(wf.workflowId(),
                wf.currentRevision().revisionNumber(), wf.status().name(),
                wf.status() == WorkflowState.COMPLETED);
    }

    /**
     * The caller's own signer slot that was signed by {@code opId}, or
     * {@code null}. Another user's operation id is never treated as a
     * replay (it must not reveal or short-circuit anything).
     */
    private static WorkflowSignerRecord acceptedOperationOf(SigningWorkflowAggregate wf,
                                                            String userId, String opId) {
        WorkflowSignerRecord slot = wf.signer();
        return slot.signed() && opId.equals(slot.signingOperationId())
                && slot.userId().equals(userId) ? slot : null;
    }

    /**
     * The ORIGINAL result of an accepted operation: the revision that
     * operation created (not whatever is current now), plus the current
     * workflow status.
     */
    private SignatureSubmissionResponse replayResponse(SigningWorkflowAggregate wf,
                                                       WorkflowSignerRecord slot) {
        int acceptedRevision = wf.revisions().stream()
                .filter(r -> r.revisionNumber() > 0 && slot.userId().equals(r.createdBy()))
                .mapToInt(DocumentRevisionRecord::revisionNumber)
                .findFirst()
                .orElse(wf.currentRevision().revisionNumber());
        return new SignatureSubmissionResponse(wf.workflowId(), acceptedRevision,
                wf.status().name(), wf.status() == WorkflowState.COMPLETED);
    }

    /**
     * Production posture (P0.1): unless the {@code dev} profile turned on
     * {@code accept-development-identities}, a fingerprint of one of the
     * publicly derivable legacy mock keys can never be bound to a slot.
     * (Current mock certificates are recognised by their subject marker at
     * submission time — a bare fingerprint does not carry the subject.)
     */
    private void rejectDevelopmentFingerprint(String fingerprint, String who) {
        if (!properties.isAcceptDevelopmentIdentities()
                && DevelopmentSigningIdentities.isLegacySharedFingerprint(fingerprint)) {
            log.warn("Rejected development mock-token certificate for {}", who);
            throw new ApiException(ApiErrorCode.DEVELOPMENT_IDENTITY_REJECTED,
                    "The certificate for " + who + " belongs to the development"
                            + " mock token, whose keys are public. Use the"
                            + " certificate of a real USB token.");
        }
    }

    private static String normalizeFingerprint(String fingerprint) {
        return fingerprint == null ? null
                : fingerprint.replace(":", "").toUpperCase(Locale.ROOT);
    }

    private static String signatureFieldFor(int order) {
        return "signature_" + order;
    }

    private WorkflowView toView(SigningWorkflowAggregate wf) {
        WorkflowSignerRecord slot = wf.signer();
        WorkflowSignerRecord current = wf.currentSigner();
        return new WorkflowView(wf.workflowId(), wf.documentName(),
                wf.status().name(), wf.currentRevision().revisionNumber(),
                slot.signed() ? 1 : 0,
                current == null ? null : current.userId(),
                current == null || !current.isBound() ? null
                        : shortFingerprint(current.certificateSha256()),
                wf.blockedReason(),
                wf.status() == WorkflowState.DRAFT && !slot.isBound() && !slot.signed());
    }

    private static String shortFingerprint(String fp) {
        if (fp == null) {
            return null;
        }
        String hex = fp.replace(":", "");
        return hex.length() <= 16 ? fp
                : hex.substring(0, 8) + "…" + hex.substring(hex.length() - 8);
    }

    /**
     * Loads the workflow and verifies it belongs to {@code userId}.
     *
     * <p>Security Phase 3: a workflow that exists but is not the caller's
     * gets exactly the same answer as one that does not exist (404, same
     * message), so workflow ids cannot be probed. Knowing an id grants
     * nothing.
     */
    private SigningWorkflowAggregate requireAccessible(String workflowId, String userId) {
        SigningWorkflowAggregate wf;
        try {
            wf = store.load(workflowId);
        } catch (IllegalArgumentException legacy) {
            // A workflow stored before SignFlow became single-signer: its
            // extra signer slots hold real signatures, so V14 deliberately
            // leaves it in the database rather than deleting it. It cannot be
            // represented here, and guessing which slot to use would put a
            // signature in the wrong place — so say so instead.
            log.warn("Workflow {} cannot be opened: {}", workflowId, legacy.getMessage());
            throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                    "This document was created when SignFlow supported several"
                            + " signers, and can no longer be opened. Its signed"
                            + " revisions are still on the server.");
        }
        boolean signer = wf != null && wf.signer().userId().equals(userId);
        boolean creator = wf != null && userId != null && userId.equals(wf.createdBy());
        if (!signer && !creator) {
            throw notFound();
        }
        return wf;
    }

    /** The single "not found / not yours" answer for workflow ids. */
    private static ApiException notFound() {
        return new ApiException(ApiErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found");
    }

    /**
     * Cancels a workflow. Terminal workflows (COMPLETED, CANCELLED, FAILED)
     * can never be cancelled — a stale cancel that races the signature gets a
     * 409 and the workflow stays COMPLETED.
     */
    public WorkflowView cancelWorkflow(String workflowId, String userId) {
        synchronized (store.lockFor(workflowId)) {
            // Mutation protocol: load INSIDE the lock, validate the fresh copy.
            SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
            if (!userId.equals(wf.createdBy())) {
                throw new ApiException(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE,
                        "Only the workflow owner can cancel this workflow");
            }
            if (!wf.status().canTransitionTo(WorkflowState.CANCELLED)) {
                throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "The workflow is " + wf.status() + " and can no longer be"
                                + " cancelled");
            }
            wf.transition(WorkflowState.CANCELLED);
            wf.appendAudit(AuditEventType.WORKFLOW_CANCELLED, userId,
                    "Workflow cancelled");
            store.save(wf);
            log.info("Workflow {} cancelled by {}", workflowId, userId);
            return toView(wf);
        }
    }

    /**
     * The one signer slot: the creator, with the certificate fingerprint they
     * supplied, or UNBOUND when they will register it from their own machine.
     */
    private WorkflowSignerRecord mapSigner(CreateWorkflowRequest request, String creator) {
        String fp = request.certificateSha256() == null
                || request.certificateSha256().isBlank()
                ? null
                : request.certificateSha256().replace(":", "").trim();
        return fp == null
                ? WorkflowSignerRecord.unbound(1, creator)
                : WorkflowSignerRecord.bound(1, creator, fp);
    }

    private void validate(CreateWorkflowRequest request, String creator) {
        if (request.documentName() == null || request.documentName().isBlank()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "documentName is required");
        }
        if (request.certificateSha256() != null
                && !request.certificateSha256().isBlank()) {
            String fingerprint = request.certificateSha256().replace(":", "").trim();
            if (!fingerprint.matches("[0-9a-fA-F]{64}")) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST,
                        "The certificate fingerprint is not a valid SHA-256 hash"
                                + " (expected 64 hex characters, e.g. AB:CD:…)."
                                + " Capture it from your USB token — or leave it"
                                + " empty and register it when you sign.");
            }
            rejectDevelopmentFingerprint(fingerprint, "signer " + creator);
        }
        // Account existence: the UserAccountService seam covers both
        // YAML-config accounts (file mode) and the users table (database mode).
        boolean known = properties.knowsUser(creator)
                || (accounts != null && accounts.knowsUser(creator));
        if (!known) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "Unknown workflow user: " + creator);
        }
    }
}

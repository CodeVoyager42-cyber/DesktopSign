package com.example.signer.server;

import com.example.signer.server.api.ApiContracts;
import com.example.signer.server.auth.UserAccountService;
import com.example.signer.server.invites.InvitationService;
import org.springframework.beans.factory.ObjectProvider;
import com.example.signer.server.api.ApiStates.InvitationStatus;
import com.example.signer.server.api.ApiStates.ParticipantState;
import com.example.signer.server.api.ApiStates.SlotState;

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
import com.example.signer.workflow.WorkflowSigningPolicy;
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
 * <p>Trust model: the PDF signature chain is the only cryptographic truth;
 * workflow state is server-owned and never taken from client claims. A
 * submission is accepted only when
 *
 * <ul>
 *   <li>the authenticated user IS the currently assigned signer,</li>
 *   <li>the workflow still expects the submitted parent revision (number AND
 *       SHA-256),</li>
 *   <li>the submitted PDF is a pure incremental update: byte-identical to the
 *       stored parent for its whole prefix,</li>
 *   <li>the full signature chain — including the new signature — verifies
 *       through the SAME verifier the desktop uses, and</li>
 *   <li>the NEW signature's certificate fingerprint matches the identity
 *       bound to the signer slot at creation time.</li>
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
     * accounts in file mode, the users table in database mode. Optional
     * because the coordinator (database mode) may rewrite email slots to
     * usernames it has just ensured to exist.
     */
    private final UserAccountService accounts;

    /** Human directory (database mode): display names for sender labels. */
    private final WorkflowDirectory directory;

    /**
     * Invitation lookup (database mode): overlays REVOKED / DECLINED /
     * EXPIRED slot states onto the signing-tasks view. Null in file mode.
     */
    private final InvitationService invitations;

    private final AtomicLong workflowSeq = new AtomicLong(System.currentTimeMillis());

    public RemoteWorkflowService(WorkflowStore store,
                                 RemoteServerProperties properties,
                                 PdfVerificationService pdfVerification,
                                 WorkflowSigningService workflowSigningService,
                                 SignaturePlacementExtractor placementExtractor,
                                 ObjectProvider<UserAccountService> accounts,
                                 ObjectProvider<WorkflowDirectory> directory,
                                 ObjectProvider<InvitationService> invitations) {
        this.store = store;
        this.properties = properties;
        this.pdfVerification = pdfVerification;
        this.workflowSigningService = workflowSigningService;
        this.placementExtractor = placementExtractor;
        this.accounts = accounts.getIfAvailable();
        this.directory = directory.getIfAvailable();
        this.invitations = invitations.getIfAvailable();
    }

    /* ------------------------------------------------------------------
     * Creation: revision 0 = manifest-embedded original (reuses the
     * existing portable-workflow domain — no signing logic duplicated)
     * ------------------------------------------------------------------ */

    /**
     * Creates a workflow from an uploaded original PDF.
     *
     * <p>When every signer arrives with a certificate fingerprint, the server
     * immediately embeds the portable manifest and stores revision 0. When
     * any signer slot is UNBOUND, the workflow stays in DRAFT with NO stored
     * document: the missing signers bind their own certificates from their
     * own machines ({@link #bindCertificate}), and the manifest-bearing
     * revision 0 is prepared only once all slots are bound. Revision 0 is
     * immutable, so binding must complete before it exists.
     */
    public WorkflowView createWorkflow(CreateWorkflowRequest request,
                                       byte[] originalPdf,
                                       String creator) {
        validate(request, creator);
        String workflowId = "WF-" + workflowSeq.incrementAndGet();
        WorkflowSigningPolicy policy = WorkflowSigningPolicy.parse(request.signingPolicy());

        SigningWorkflowAggregate aggregate = SigningWorkflowAggregate.create(
                workflowId, request.documentName(), policy,
                mapSigners(request), creator);
        aggregate.appendAudit(AuditEventType.WORKFLOW_CREATED, creator,
                "Workflow created with " + request.signers().size() + " signer(s)"
                        + " (" + policy + " policy)");

        synchronized (store.lockFor(workflowId)) {
            boolean allBound = aggregate.allBound();
            if (!allBound) {
                long unbound = aggregate.pendingSigners().stream()
                        .filter(s -> !s.isBound()).count();
                aggregate.appendAudit(AuditEventType.WORKFLOW_CREATED, creator,
                        unbound + " signer(s) must bind a certificate before the"
                                + " document is prepared");
                store.stageOriginal(workflowId, originalPdf);
                store.save(aggregate);
                log.info("Workflow {} created by {} — DRAFT, {} certificate(s) pending",
                        workflowId, creator, unbound);
                return toView(aggregate);
            }
            prepareWorkflow(aggregate, originalPdf, creator);
            return toView(aggregate);
        }
    }

    /**
     * Binds the authenticated signer's certificate to their UNBOUND slot
     * (certificate enrollment, §14/§17 of the task spec). Only the slot's own
     * user may bind it, only while the workflow is DRAFT. Binding the last
     * missing certificate prepares the document (revision 0) atomically.
     */
    public WorkflowView bindCertificate(String workflowId, String userId,
                                        BindCertificateRequest body) {
        String fingerprint = body == null || body.certificateSha256() == null
                ? "" : body.certificateSha256().replace(":", "").trim();
        if (!fingerprint.matches("[0-9a-fA-F]{64}")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "The certificate fingerprint must be 64 hex characters (the"
                            + " SHA-256 of the signer certificate, captured from"
                            + " the signer's USB token)");
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
            WorkflowSignerRecord slot = wf.signers().stream()
                    .filter(s -> s.userId().equals(userId))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE,
                            "You are not a signer of this workflow"));
            if (slot.signed()) {
                throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "Your signature is already recorded");
            }
            wf.bindCertificate(userId, fingerprint.toUpperCase(Locale.ROOT));
            wf.appendAudit(AuditEventType.CERTIFICATE_BOUND, userId,
                    "Certificate bound to signer " + slot.order() + " ("
                            + shortFingerprint(fingerprint) + ")");
            if (wf.allBound()) {
                byte[] original = store.readStagedOriginal(workflowId);
                if (original == null) {
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                            "The staged original document is missing — the"
                                    + " workflow cannot be prepared");
                }
                prepareWorkflow(wf, original, wf.createdBy());
                log.info("Workflow {}: last certificate bound by {} — document"
                                + " prepared", workflowId, userId);
            } else {
                store.save(wf);
            }
            return toView(wf);
        }
    }

    /**
     * Embeds the manifest into the original, stores immutable revision 0 and
     * activates the first signing turn. Caller must hold the workflow lock.
     */
    private void prepareWorkflow(SigningWorkflowAggregate wf, byte[] originalPdf,
                                 String actor) {
        List<WorkflowSignerStep> steps = new ArrayList<>();
        for (WorkflowSignerRecord s : wf.signers()) {
            CertificateIdentity identity = s.isBound()
                    ? new CertificateIdentity(s.certificateSha256(), "", "", "")
                    : CertificateIdentity.unbound();
            steps.add(WorkflowSignerStep.of(s.order(), "Signer " + s.order(),
                    identity, null));
        }
        Path original = null;
        Path prepared = null;
        try {
            original = Files.createTempFile("wf-create-", ".pdf");
            Files.write(original, originalPdf);
            WorkflowManifest manifest = workflowSigningService.createWorkflow(
                    original, wf.documentName(), wf.policy(), steps);
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
                        "Revision 0 stored (" + revision0.length + " bytes) — all"
                                + " certificates bound");
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

    /** The workflow view for participants (and the creator). */
    public WorkflowView getWorkflow(String workflowId, String userId) {
        return toView(requireAccessible(workflowId, userId));
    }

    /**
     * The authenticated user's task in {@code workflowId}. When the workflow
     * is FLEXIBLE and the turn is unclaimed, {@code waitingForClaim} is true
     * and {@code eligibleSignerUserIds} lists who may claim.
     */
    public TaskView getTask(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        WorkflowSignerRecord signer = wf.currentSigner();
        boolean waitingForClaim = signer == null;
        List<String> eligible = waitingForClaim
                ? wf.pendingSigners().stream()
                        .map(WorkflowSignerRecord::userId).toList()
                : List.of();
        List<TaskView.StepView> steps = wf.signers().stream()
                .map(s -> new TaskView.StepView(s.order(), s.userId(),
                        s.signed(),
                        signer != null && s.order() == signer.order(),
                        s.isBound(), signerState(s)))
                .toList();
        log.info("Signing task opened for workflow {} by {} (status {}, "
                        + "{}/{} signature(s) accepted)", wf.workflowId(), userId,
                wf.status(), steps.stream().filter(TaskView.StepView::signed).count(),
                steps.size());
        return new TaskView(wf.workflowId(), wf.documentName(), wf.status().name(),
                signer == null ? null : signer.order(),
                signer == null ? null : signatureFieldFor(signer.order()),
                wf.currentRevision().revisionNumber(), wf.currentRevision().sha256(),
                waitingForClaim, eligible, steps);
    }

    /* ------------------------------------------------------------------
     * Explicit, server-derived signer lifecycle
     * ------------------------------------------------------------------ */

    /**
     * The signer lifecycle of ONE slot, derived from server-owned facts
     * only:
     *
     * <pre>
     * INVITED  — the invited recipient has no registered account yet
     * PENDING  — the recipient has an account, but no accepted signature
     * SIGNED   — an accepted signature exists (validated submission only)
     * </pre>
     *
     * <p>Deliberately NOT derived from: the invitation's own status, the
     * workflow's status, whether the document was opened/downloaded, or
     * anything the client sends. Registration and authentication never move
     * a slot past PENDING; only an accepted signature reaches SIGNED.
     */
    private String signerState(WorkflowSignerRecord slot) {
        if (slot.signed()) {
            return "SIGNED";
        }
        return isRegistered(slot.userId()) ? "PENDING" : "INVITED";
    }

    /** Whether the slot's account is registered (can authenticate). */
    private boolean isRegistered(String userId) {
        return accounts == null || accounts.isRegistered(userId);
    }

    /**
     * Atomically claims the flexible signing turn for the authenticated
     * user. Re-claiming your own turn refreshes it; a claim held by another
     * signer yields {@code TURN_ALREADY_CLAIMED} (409).
     */
    public TaskView claimTurn(String workflowId, String userId) {
        synchronized (store.lockFor(workflowId)) {
            // Mutation protocol: load INSIDE the lock, validate the fresh copy.
            SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
            if (wf.status() != WorkflowState.WAITING_FOR_SIGNER) {
                throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "The workflow is not accepting signatures (status "
                                + wf.status() + ")");
            }
            try {
                wf.claimTurn(userId);
            } catch (IllegalStateException e) {
                throw new ApiException(ApiErrorCode.TURN_ALREADY_CLAIMED, e.getMessage());
            }
            wf.appendAudit(AuditEventType.SIGNING_TURN_CLAIMED, userId,
                    "Signing turn claimed by " + userId);
            store.save(wf);
        }
        return getTask(workflowId, userId);
    }

    /** Pending signer user ids of an active workflow (claim candidates). */
    public List<String> eligibleSigners(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        if (wf.status() != WorkflowState.WAITING_FOR_SIGNER) {
            return List.of();
        }
        return wf.pendingSigners().stream()
                .map(WorkflowSignerRecord::userId).toList();
    }

    /** The current revision bytes — only for the signer whose turn it is. */
    public byte[] getDocument(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        WorkflowSignerRecord signer = wf.currentSigner();
        if (signer == null || !signer.userId().equals(userId)) {
            throw new ApiException(ApiErrorCode.NOT_YOUR_TURN,
                    "It is not your turn to sign this workflow");
        }
        return store.readRevision(workflowId, wf.currentRevision().revisionNumber());
    }

    /** Revision history visible to participants. */
    public List<RevisionView> getRevisions(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        return wf.revisions().stream()
                .map(r -> new RevisionView(r.revisionNumber(), r.parentRevision(),
                        r.sha256(), r.sizeBytes(), r.createdAt().toString(),
                        r.createdBy()))
                .toList();
    }

    /** Audit trail visible to participants. */
    public List<AuditView> getAudit(String workflowId, String userId) {
        SigningWorkflowAggregate wf = requireAccessible(workflowId, userId);
        return wf.audit().stream()
                .map(a -> new AuditView(a.sequence(), a.eventType().name(),
                        a.actor(), a.detail(), a.occurredAt().toString()))
                .toList();
    }

    /** Workflows where {@code userId} holds the current signing task. */
    public List<WorkflowView> myTasks(String userId) {
        List<WorkflowView> result = new ArrayList<>();
        for (String workflowId : store.listWorkflowIdsForSigner(userId)) {
            SigningWorkflowAggregate wf;
            try {
                wf = store.load(workflowId);
            } catch (RuntimeException e) {
                log.warn("Skipping unreadable workflow {}", workflowId);
                continue;
            }
            if (wf == null) {
                continue;
            }
            if (wf.status() == WorkflowState.WAITING_FOR_SIGNER) {
                boolean mine = wf.currentSigner() != null
                        ? wf.maySubmit(userId)
                        : wf.policy() == WorkflowSigningPolicy.FLEXIBLE
                                && wf.isEligibleSigner(userId);
                if (mine) {
                    result.add(toView(wf));
                }
            } else if (wf.status() == WorkflowState.DRAFT
                    && wf.signers().stream().anyMatch(s ->
                            s.userId().equals(userId) && !s.isBound() && !s.signed())) {
                // The user still owes a certificate binding — surface it.
                result.add(toView(wf));
            }
        }
        return result;
    }

    /**
     * Workflows created by {@code userId} (the inviter's picker — the owner
     * invites signers into workflows they created). Cancelled workflows are
     * hidden; DRAFT workflows appear so their unbound slots can be invited.
     */
    public List<WorkflowView> ownedBy(String userId) {
        List<WorkflowView> result = new ArrayList<>();
        for (String workflowId : store.listWorkflowIdsCreatedBy(userId)) {
            SigningWorkflowAggregate wf;
            try {
                wf = store.load(workflowId);
            } catch (RuntimeException e) {
                log.warn("Skipping unreadable workflow {}", workflowId);
                continue;
            }
            if (wf == null) {
                continue;
            }
            if (userId != null && userId.equals(wf.createdBy())
                    && wf.status() != WorkflowState.CANCELLED) {
                result.add(toView(wf));
            }
        }
        return result;
    }

    /* ------------------------------------------------------------------
     * Signing Tasks workspace (participant-scoped, all lifecycle states)
     * ------------------------------------------------------------------ */

    /**
     * Every workflow the signed-in user participates in as a SIGNER, across
     * all lifecycle states — unlike {@link #myTasks}, which returns only
     * actionable items. This is the data behind the desktop "Signing
     * Tasks" workspace; cancelled workflows are hidden (the participant's
     * action is gone, keeping them visible only in the owner's tracking).
     */
    public SigningTasksView listParticipating(String userId) {
        List<TaskItemView> tasks = new ArrayList<>();
        // Only the caller's workflows are loaded (one indexed query in
        // database mode) — before, EVERY workflow was loaded on each refresh.
        for (String workflowId : store.listWorkflowIdsForSigner(userId)) {
            SigningWorkflowAggregate wf;
            try {
                wf = store.load(workflowId);
            } catch (RuntimeException e) {
                log.warn("Skipping unreadable workflow {}", workflowId);
                continue;
            }
            if (wf == null) {
                continue;
            }
            WorkflowSignerRecord mine = wf.signers().stream()
                    .filter(s -> s.userId().equals(userId))
                    .findFirst().orElse(null);
            if (mine == null) {
                continue;
            }
            tasks.add(toTaskItem(wf, mine));
        }
        tasks.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                a.documentName(), b.documentName()));
        return new SigningTasksView(tasks);
    }

    /** Maps one aggregate + the caller's slot to the task-item view. */
    private TaskItemView toTaskItem(SigningWorkflowAggregate wf,
                                    WorkflowSignerRecord mine) {
        String senderId = wf.createdBy();
        WorkflowDirectory.Profile senderProfile = senderId == null
                ? null : directory == null ? null : directory.profileOf(senderId);

        // Participant state: the slot's OWN signature first, then the
        // workflow lifecycle, then the slot's invitation overlay (REVOKED /
        // DECLINED / EXPIRED in database mode).
        //
        // SIGNED is emitted for this participant ONLY when their own slot
        // carries an accepted signature — never because the workflow is
        // COMPLETED, never because an account/invitation exists, never
        // because the document was opened.
        boolean slotSigned = mine.signed();
        ParticipantState label;
        boolean waitingForMe = false;
        boolean waitingForClaim = false;
        if (slotSigned) {
            label = ParticipantState.SIGNED;
        } else if (wf.status() == WorkflowState.DRAFT && !mine.isBound()) {
            label = ParticipantState.CERTIFICATE_PENDING;
        } else if (!isRegistered(mine.userId())) {
            // An invitation exists for a recipient who has not registered
            // yet: invitation state and account state, NOT a signature.
            label = ParticipantState.INVITED;
        } else if (wf.status() == WorkflowState.CANCELLED) {
            // The requester withdrew the request: shown (not hidden) so the
            // participant learns why it disappeared from "Action required".
            label = ParticipantState.CANCELLED;
        } else if (wf.status() == WorkflowState.FAILED) {
            label = ParticipantState.BLOCKED;
        } else if (wf.status() == WorkflowState.WAITING_FOR_SIGNER) {
            WorkflowSignerRecord current = wf.currentSigner();
            if (current != null && mine.userId().equals(current.userId())) {
                label = ParticipantState.YOUR_TURN;
                waitingForMe = true;
            } else if (current == null
                    && wf.policy() == WorkflowSigningPolicy.FLEXIBLE
                    && wf.isEligibleSigner(mine.userId())) {
                label = ParticipantState.WAITING_FOR_CLAIM;
                waitingForClaim = true;
            } else {
                label = ParticipantState.WAITING_FOR_OTHERS;
            }
        } else {
            label = ParticipantState.IN_PROGRESS;
        }
        InvitationService.SlotInvitationState
                slotInvitation = invitations == null ? null
                : invitations.latestSlotState(wf.workflowId(), mine.userId());
        if (!slotSigned) {
            if (slotInvitation != null
                    && InvitationStatus.parse(slotInvitation.status()) == InvitationStatus.REVOKED) {
                label = ParticipantState.REVOKED;
                waitingForMe = false;
                waitingForClaim = false;
            } else if (slotInvitation != null
                    && InvitationStatus.parse(slotInvitation.status()) == InvitationStatus.DECLINED) {
                label = ParticipantState.DECLINED;
                waitingForMe = false;
                waitingForClaim = false;
            } else if (slotInvitation != null
                    && InvitationStatus.parse(slotInvitation.status()) == InvitationStatus.EXPIRED) {
                label = ParticipantState.EXPIRED;
                waitingForMe = false;
                waitingForClaim = false;
            }
        }

        // Dates: created = the workflow's first audit event (WORKFLOW_CREATED
        // or SIGNER_REGISTERED, whichever ran first); completed = the last
        // audit event when the workflow is terminal.
        String createdAt = null;
        String completedAt = null;
        List<AuditEventRecord> audit = wf.audit();
        if (!audit.isEmpty()) {
            createdAt = audit.get(0).occurredAt().toString();
            // Per-participant: the "completed" date is when THIS slot's own
            // signature was accepted, so an unsigned participant is never
            // shown as having completed the document. The workflow's last
            // audit event is only a fallback for legacy rows.
            if (slotSigned) {
                completedAt = mine.signedAt() != null
                        ? mine.signedAt().toString()
                        : audit.get(audit.size() - 1).occurredAt().toString();
            }
        }
        int completed = (int) wf.signers().stream()
                .filter(WorkflowSignerRecord::signed).count();

        // Signing order as the participant sees it: names only (no emails),
        // each slot SIGNED / CURRENT (holds the turn) / WAITING.
        WorkflowSignerRecord current = wf.currentSigner();
        List<ApiContracts.TaskParticipantView> participants =
                new ArrayList<>();
        for (WorkflowSignerRecord s : wf.signers()) {
            WorkflowDirectory.Profile profile = directory == null ? null
                    : directory.profileOf(s.userId());
            String name = profile != null && profile.displayName() != null
                    && !profile.displayName().isBlank() ? profile.displayName() : s.userId();
            SlotState state = s.signed() ? SlotState.SIGNED
                    : current != null && current.userId().equals(s.userId()) ? SlotState.CURRENT
                    : SlotState.WAITING;
            participants.add(new ApiContracts.TaskParticipantView(
                    s.order(), name, s.userId().equals(mine.userId()), state));
        }
        String expiresAt = !slotSigned && slotInvitation != null
                && (InvitationStatus.parse(slotInvitation.status()) == InvitationStatus.PENDING
                        || InvitationStatus.parse(slotInvitation.status()) == InvitationStatus.ACCEPTED)
                ? slotInvitation.expiresAt() : null;
        String updatedAt = audit.isEmpty() ? null
                : audit.get(audit.size() - 1).occurredAt().toString();
        return new TaskItemView(
                wf.workflowId(),
                wf.documentName(),
                senderId,
                senderProfile == null ? null : senderProfile.displayName(),
                wf.status().name(),
                createdAt,
                completedAt,
                new ParticipantStateView(label,
                        mine.order(), slotSigned, waitingForMe, waitingForClaim),
                wf.signers().size(),
                completed,
                wf.policy().name(),
                participants,
                expiresAt,
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

            // ── state and turn ──
            if (wf.status() != WorkflowState.WAITING_FOR_SIGNER) {
                throw new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "The workflow is not waiting for a signature (status "
                                + wf.status() + ")");
            }
            WorkflowSignerRecord current = wf.currentSigner();
            if (current == null) {
                throw new ApiException(ApiErrorCode.NOT_YOUR_TURN,
                        wf.policy() == WorkflowSigningPolicy.FLEXIBLE
                                ? "No signing turn is claimed — claim the turn"
                                        + " first (POST /claim)"
                                : "It is not your turn to sign this workflow");
            }
            if (!current.userId().equals(userId)) {
                throw new ApiException(ApiErrorCode.NOT_YOUR_TURN,
                        wf.policy() == WorkflowSigningPolicy.FLEXIBLE
                                ? "The signing turn is currently held by "
                                        + current.userId()
                                : "It is not your turn to sign this workflow");
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
        {
            wf.appendRevision(new DocumentRevisionRecord(newRevision,
                    expectedRevision, WorkflowFileStore.sha256Hex(pdfBytes),
                    pdfBytes.length, Instant.now(), userId));
            wf.appendAudit(AuditEventType.REVISION_CREATED, userId,
                    "Revision " + newRevision + " accepted ("
                            + pdfBytes.length + " bytes)");
            // Durable placement metadata (brief §33): read the accepted
            // visible-signature geometry out of the accepted revision's
            // embedded manifest. Metadata only — never a rejection reason.
            SignaturePlacement acceptedPlacement =
                    placementExtractor.extract(workflowId, pdfBytes, expectedRevision);
            if (acceptedPlacement != null) {
                wf.appendAudit(AuditEventType.SIGNATURE_PLACEMENT_RECORDED, userId,
                        "Signer " + current.order() + " — page "
                                + (acceptedPlacement.pageIndex() + 1) + " ("
                                + (int) acceptedPlacement.x() + ", "
                                + (int) acceptedPlacement.y() + ")");
            }
            WorkflowSignerRecord next = wf.markSignedByUser(userId,
                    body.signingOperationId(), acceptedPlacement);
            wf.appendAudit(AuditEventType.SIGNER_COMPLETED, userId,
                    "Signer " + current.order() + " (" + current.userId()
                            + ") completed");
            wf.transition(WorkflowState.SIGNATURE_SUBMITTED);
            wf.transition(WorkflowState.VALIDATING);
            boolean completed = wf.allSigned();
            wf.transition(completed
                    ? WorkflowState.COMPLETED
                    : WorkflowState.WAITING_FOR_SIGNER);
            if (completed) {
                wf.appendAudit(AuditEventType.WORKFLOW_COMPLETED, userId,
                        "All " + wf.signers().size() + " signatures completed");
            } else if (next != null) {
                wf.appendAudit(AuditEventType.NEXT_SIGNER_ACTIVATED, userId,
                        "Signer " + next.order() + " (" + next.userId()
                                + ") is now current");
            } else {
                wf.appendAudit(AuditEventType.SIGNING_TURN_RELEASED, userId,
                        "Flexible turn is open for claims ("
                                + wf.pendingSigners().size() + " pending)");
            }
            store.save(wf);
            log.info("Workflow {}: revision {} accepted from {} ({} signatures done)",
                    workflowId, newRevision, userId,
                    wf.signers().stream().filter(WorkflowSignerRecord::signed).count());
            return currentResponse(wf);
        }
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
            //    the certificate bound to this signer slot at creation.
            SignatureVerificationResult.SignatureDetail newest =
                    result.signatures().get(result.signatures().size() - 1);
            String actualFp = normalizeFingerprint(newest.signerCertFingerprint());
            if (!properties.isAcceptDevelopmentIdentities()
                    && DevelopmentSigningIdentities.isDevelopmentIdentity(
                            newest.signerSubject(), actualFp)) {
                wf.appendAudit(AuditEventType.SIGNATURE_VALIDATION_FAILED, userId,
                        "Rejected a signature made with a development mock-token"
                                + " certificate (signer " + current.order() + ")");
                store.save(wf);
                throw new ApiException(ApiErrorCode.DEVELOPMENT_IDENTITY_REJECTED,
                        "This signature was created with the development mock"
                                + " token. Sign with your real USB token.");
            }
            String expectedFp = normalizeFingerprint(current.certificateSha256());
            if (actualFp == null || expectedFp == null || !actualFp.equals(expectedFp)) {
                wf.appendAudit(AuditEventType.CERTIFICATE_MISMATCH_REJECTED, userId,
                        "New signature does not match the certificate assigned to "
                                + "signer " + current.order());
                store.save(wf);
                throw new ApiException(ApiErrorCode.CERTIFICATE_MISMATCH,
                        "The signature was not created by the certificate assigned "
                                + "to this signing step");
            }
            wf.appendAudit(AuditEventType.SIGNATURE_VALIDATED, userId,
                    "Signature validated for signer " + current.order());
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
        WorkflowSignerRecord next = wf.currentSigner();
        return new SignatureSubmissionResponse(wf.workflowId(),
                wf.currentRevision().revisionNumber(), wf.status().name(),
                next == null ? null : next.userId(),
                wf.status() == WorkflowState.COMPLETED);
    }

    /**
     * The caller's own signer slot that was signed by {@code opId}, or
     * {@code null}. Another user's operation id is never treated as a
     * replay (it must not reveal or short-circuit anything).
     */
    private static WorkflowSignerRecord acceptedOperationOf(SigningWorkflowAggregate wf,
                                                            String userId, String opId) {
        return wf.signers().stream()
                .filter(s -> s.signed() && opId.equals(s.signingOperationId())
                        && s.userId().equals(userId))
                .findFirst()
                .orElse(null);
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
        WorkflowSignerRecord next = wf.currentSigner();
        return new SignatureSubmissionResponse(wf.workflowId(), acceptedRevision,
                wf.status().name(), next == null ? null : next.userId(),
                wf.status() == WorkflowState.COMPLETED);
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
        WorkflowSignerRecord current = wf.currentSigner();
        return new WorkflowView(wf.workflowId(), wf.documentName(),
                wf.status().name(), wf.currentRevision().revisionNumber(),
                (int) wf.signers().stream().filter(WorkflowSignerRecord::signed).count(),
                wf.signers().size(),
                current == null ? null : current.userId(),
                current == null || !current.isBound() ? null
                        : shortFingerprint(current.certificateSha256()),
                wf.blockedReason(),
                wf.policy().name(),
                wf.status() == WorkflowState.WAITING_FOR_SIGNER && current == null,
                wf.status() == WorkflowState.DRAFT
                        && wf.signers().stream().anyMatch(s -> !s.isBound() && !s.signed()));
    }

    private static String shortFingerprint(String fp) {
        if (fp == null) {
            return null;
        }
        String hex = fp.replace(":", "");
        return hex.length() <= 16 ? fp
                : hex.substring(0, 8) + "…" + hex.substring(hex.length() - 8);
    }

    /** Loads the workflow and verifies {@code userId} is a participant. */
    private SigningWorkflowAggregate requireAccessible(String workflowId, String userId) {
        SigningWorkflowAggregate wf = store.load(workflowId);
        if (wf == null) {
            throw new ApiException(ApiErrorCode.WORKFLOW_NOT_FOUND,
                    "No workflow " + workflowId);
        }
        boolean participant = wf.signers().stream()
                .anyMatch(s -> s.userId().equals(userId));
        boolean creator = userId != null && userId.equals(wf.createdBy());
        if (!participant && !creator) {
            throw new ApiException(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE,
                    "You are not a participant in this workflow");
        }
        return wf;
    }

    /**
     * Cancels a workflow. Only the workflow OWNER (its creator) may cancel;
     * signers decline their invitation instead. Terminal workflows
     * (COMPLETED, CANCELLED, FAILED) can never be cancelled — a stale cancel
     * that races the final signature gets a 409 and the workflow stays
     * COMPLETED.
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
            wf.releaseTurn();
            wf.appendAudit(AuditEventType.WORKFLOW_CANCELLED, userId,
                    "Workflow cancelled");
            store.save(wf);
            log.info("Workflow {} cancelled by {}", workflowId, userId);
            return toView(wf);
        }
    }

    private List<WorkflowSignerRecord> mapSigners(CreateWorkflowRequest request) {
        return request.signers().stream()
                .map(s -> {
                    String fp = s.certificateSha256() == null
                            || s.certificateSha256().isBlank()
                            ? null
                            : s.certificateSha256().replace(":", "").trim();
                    return fp == null
                            ? WorkflowSignerRecord.unbound(s.order(), s.userId())
                            : WorkflowSignerRecord.bound(s.order(), s.userId(), fp);
                })
                .toList();
    }

    private void validate(CreateWorkflowRequest request, String creator) {
        if (request.documentName() == null || request.documentName().isBlank()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "documentName is required");
        }
        try {
            WorkflowSigningPolicy.parse(request.signingPolicy());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, e.getMessage());
        }
        if (request.signers() == null || request.signers().isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "A workflow needs at least one signer");
        }
        for (int i = 0; i < request.signers().size(); i++) {
            CreateWorkflowRequest.SignerSpec s = request.signers().get(i);
            if (s.order() != i + 1) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST,
                        "Signer order must be 1..N without gaps");
            }
            if (s.userId() == null || s.userId().isBlank()) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST,
                        "Each signer needs a userId");
            }
            if (s.certificateSha256() != null && !s.certificateSha256().isBlank()) {
                String fingerprint = s.certificateSha256().replace(":", "").trim();
                if (!fingerprint.matches("[0-9a-fA-F]{64}")) {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST,
                            "Signer " + s.order() + " (" + s.userId()
                                    + "): the certificate fingerprint is not a valid"
                                    + " SHA-256 hash (expected 64 hex characters, e.g."
                                    + " AB:CD:…). Capture it from the signer's USB token"
                                    + " — or leave it empty to let the signer bind"
                                    + " their own certificate.");
                }
                rejectDevelopmentFingerprint(fingerprint,
                        "signer " + s.order() + " (" + s.userId() + ")");
            }
            // Account existence: the UserAccountService seam covers both
            // YAML-config accounts (file mode) and the users table (database
            // mode); the invitation coordinator pre-creates invited accounts.
            boolean known = properties.knowsUser(s.userId())
                    || (accounts != null && accounts.knowsUser(s.userId()));
            if (!known) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST,
                        "Unknown workflow user: " + s.userId());
            }
        }
    }
}

package com.example.signer.server;

import com.example.signer.signing.SignaturePlacement;
import com.example.signer.workflow.WorkflowSigningPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The owning aggregate of a server-coordinated signing workflow.
 *
 * <p>All state changes funnel through {@link #transition} (state-machine
 * guarded), {@link #claimTurn}, {@link #markSignedByUser}, {@link #appendRevision}
 * and {@link #appendAudit}, keeping the invariants — contiguous revision
 * chain, contiguous signer order, at most one claimed turn — in one place.
 * The aggregate performs NO persistence and NO locking; the service
 * serialises access with the per-workflow lock.
 *
 * <p>Turn model (the server is authoritative, never the client):
 * <ul>
 *   <li>{@code SEQUENTIAL}: the turn belongs to the first unsigned signer in
 *       order. {@code claimTurn} verifies the caller IS that signer.</li>
 *   <li>{@code FLEXIBLE}: any pending signer may claim the single turn; only
 *       the holder may submit. The claim is persisted with a timestamp so it
 *       survives restarts, and expires after {@link #CLAIM_TIMEOUT}: another
 *       eligible signer may then supersede it (escape hatch if the holder's
 *       machine dies mid-turn). Simultaneous claims within the TTL: the first
 *       wins, the loser gets a conflict.</li>
 * </ul>
 */
public final class SigningWorkflowAggregate {

    /** A flexible turn claim older than this may be superseded. */
    public static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(30);

    /**
     * Version of an aggregate that has never been persisted. Stores refuse
     * to save a NEW aggregate over an existing workflow, and refuse to save
     * a persisted aggregate whose version no longer matches the stored one
     * (optimistic concurrency: a stale copy can never overwrite newer state).
     */
    public static final long NEW = -1L;

    private final String workflowId;
    private final String documentName;
    private final WorkflowSigningPolicy policy;
    private final List<WorkflowSignerRecord> signers;
    private final List<DocumentRevisionRecord> revisions;
    private final List<AuditEventRecord> audit;

    private WorkflowState status;
    private String blockedReason;
    private String claimedTurnUserId;
    private Instant claimedTurnAt;
    private String createdBy;

    /** Stored version this copy was loaded from ({@link #NEW} if never saved). */
    private long version = NEW;

    /**
     * Number of audit events that already exist in storage. Events beyond
     * this index were appended in memory since the load and are the only
     * ones a store inserts; their final sequence numbers are assigned by
     * the store under the workflow lock, never trusted from memory.
     */
    private int persistedAuditCount;

    private SigningWorkflowAggregate(String workflowId,
                                     String documentName,
                                     WorkflowSigningPolicy policy,
                                     List<WorkflowSignerRecord> signers,
                                     List<DocumentRevisionRecord> revisions,
                                     List<AuditEventRecord> audit,
                                     WorkflowState status,
                                     String blockedReason,
                                     String claimedTurnUserId,
                                     Instant claimedTurnAt,
                                     String createdBy) {
        this.workflowId = workflowId;
        this.documentName = documentName;
        this.policy = policy;
        this.signers = new ArrayList<>(signers);
        this.revisions = new ArrayList<>(revisions);
        this.audit = new ArrayList<>(audit);
        this.status = status;
        this.blockedReason = blockedReason;
        this.claimedTurnUserId = claimedTurnUserId;
        this.claimedTurnAt = claimedTurnAt;
        this.createdBy = createdBy;
    }

    /** Rehydrates an aggregate from storage; validates internal consistency. */
    public static SigningWorkflowAggregate rehydrate(String workflowId,
                                                     String documentName,
                                                     WorkflowSigningPolicy policy,
                                                     List<WorkflowSignerRecord> signers,
                                                     List<DocumentRevisionRecord> revisions,
                                                     List<AuditEventRecord> audit,
                                                     WorkflowState status,
                                                     String blockedReason) {
        return rehydrate(workflowId, documentName, policy, signers, revisions,
                audit, status, blockedReason, null, null, null);
    }

    /** Full rehydration including a persisted turn claim. */
    public static SigningWorkflowAggregate rehydrate(String workflowId,
                                                     String documentName,
                                                     WorkflowSigningPolicy policy,
                                                     List<WorkflowSignerRecord> signers,
                                                     List<DocumentRevisionRecord> revisions,
                                                     List<AuditEventRecord> audit,
                                                     WorkflowState status,
                                                     String blockedReason,
                                                     String claimedTurnUserId,
                                                     Instant claimedTurnAt) {
        return rehydrate(workflowId, documentName, policy, signers, revisions,
                audit, status, blockedReason, claimedTurnUserId, claimedTurnAt,
                null);
    }

    /**
     * Rehydration by a {@link WorkflowStore}: the stored state plus the
     * stored {@code version} the copy was read at. Every audit event passed
     * in is considered already persisted.
     */
    public static SigningWorkflowAggregate rehydrateStored(String workflowId,
                                                           String documentName,
                                                           WorkflowSigningPolicy policy,
                                                           List<WorkflowSignerRecord> signers,
                                                           List<DocumentRevisionRecord> revisions,
                                                           List<AuditEventRecord> audit,
                                                           WorkflowState status,
                                                           String blockedReason,
                                                           String claimedTurnUserId,
                                                           Instant claimedTurnAt,
                                                           String createdBy,
                                                           long version) {
        SigningWorkflowAggregate wf = rehydrate(workflowId, documentName, policy,
                signers, revisions, audit, status, blockedReason,
                claimedTurnUserId, claimedTurnAt, createdBy);
        wf.version = version;
        return wf;
    }

    /** Full rehydration including a persisted turn claim and the creator. */
    public static SigningWorkflowAggregate rehydrate(String workflowId,
                                                     String documentName,
                                                     WorkflowSigningPolicy policy,
                                                     List<WorkflowSignerRecord> signers,
                                                     List<DocumentRevisionRecord> revisions,
                                                     List<AuditEventRecord> audit,
                                                     WorkflowState status,
                                                     String blockedReason,
                                                     String claimedTurnUserId,
                                                     Instant claimedTurnAt,
                                                     String createdBy) {
        if (signers == null || signers.isEmpty()) {
            throw new IllegalArgumentException("a workflow needs at least one signer");
        }
        for (int i = 0; i < signers.size(); i++) {
            if (signers.get(i).order() != i + 1) {
                throw new IllegalArgumentException("signer order must be 1..N without gaps");
            }
        }
        if (revisions == null || revisions.isEmpty()) {
            throw new IllegalArgumentException("a workflow needs at least revision 0");
        }
        for (int i = 0; i < revisions.size(); i++) {
            if (revisions.get(i).revisionNumber() != i) {
                throw new IllegalArgumentException("revisions must be contiguous from 0");
            }
        }
        SigningWorkflowAggregate wf = new SigningWorkflowAggregate(workflowId, documentName,
                policy == null ? WorkflowSigningPolicy.SEQUENTIAL : policy,
                signers, revisions, audit == null ? List.of() : audit, status, blockedReason,
                claimedTurnUserId, claimedTurnAt, createdBy);
        wf.persistedAuditCount = wf.audit.size();
        return wf;
    }

    /**
     * Creates a fresh DRAFT workflow. DRAFT = no real revision 0 yet:
     * workflows with unbound signer slots stay in DRAFT until every signer
     * has bound a certificate (revision 0 embeds the manifest that lists the
     * bound identities, so it can only be written once all are known).
     */
    public static SigningWorkflowAggregate create(String workflowId,
                                                  String documentName,
                                                  WorkflowSigningPolicy policy,
                                                  List<WorkflowSignerRecord> signers,
                                                  String createdBy) {
        DocumentRevisionRecord zero = new DocumentRevisionRecord(0, -1,
                "0".repeat(64), 0, Instant.now(), "system");
        return rehydrate(workflowId, documentName, policy, signers,
                List.of(zero), List.of(), WorkflowState.DRAFT, null,
                null, null, createdBy);
    }

    /** The stored version this copy was loaded at, or {@link #NEW}. */
    public long version() {
        return version;
    }

    /** Whether this aggregate has never been persisted. */
    public boolean isNew() {
        return version == NEW;
    }

    /** Audit events appended since the load (to be inserted by the store). */
    public List<AuditEventRecord> unsavedAuditEvents() {
        return List.copyOf(audit.subList(persistedAuditCount, audit.size()));
    }

    /**
     * Called by a store after a successful save: this copy now reflects
     * stored {@code newVersion} and every audit event is persisted.
     */
    public void markPersisted(long newVersion) {
        this.version = newVersion;
        this.persistedAuditCount = audit.size();
    }

    /**
     * A copy of this aggregate that a store treats as never persisted (used
     * when importing a workflow from another store).
     */
    public SigningWorkflowAggregate asNewForImport() {
        SigningWorkflowAggregate copy = rehydrate(workflowId, documentName, policy,
                signers, revisions, List.of(), status, blockedReason,
                claimedTurnUserId, claimedTurnAt, createdBy);
        copy.audit.addAll(audit);
        return copy;
    }

    /** Whether the workflow is in a terminal state (nothing may change it). */
    public boolean isTerminal() {
        return status == WorkflowState.COMPLETED
                || status == WorkflowState.CANCELLED
                || status == WorkflowState.FAILED;
    }

    public String workflowId() {
        return workflowId;
    }

    public String documentName() {
        return documentName;
    }

    /** The authenticated user who created the workflow (participant or not). */
    public String createdBy() {
        return createdBy;
    }

    public WorkflowSigningPolicy policy() {
        return policy;
    }

    public WorkflowState status() {
        return status;
    }

    public String blockedReason() {
        return blockedReason;
    }

    public List<WorkflowSignerRecord> signers() {
        return List.copyOf(signers);
    }

    public List<DocumentRevisionRecord> revisions() {
        return List.copyOf(revisions);
    }

    public List<AuditEventRecord> audit() {
        return List.copyOf(audit);
    }

    /** The newest accepted revision — what the current signer must download. */
    public DocumentRevisionRecord currentRevision() {
        return revisions.get(revisions.size() - 1);
    }

    /**
     * The signer who holds the signing turn, or {@code null} when none
     * applies: FLEXIBLE with no (unexpired) claim, or all signed.
     */
    public WorkflowSignerRecord currentSigner() {
        List<WorkflowSignerRecord> pending = pendingSigners();
        if (pending.isEmpty()) {
            return null;
        }
        if (policy == WorkflowSigningPolicy.SEQUENTIAL) {
            return pending.get(0);
        }
        if (claimedTurnUserId == null || isClaimExpired()) {
            return null;
        }
        return pending.stream()
                .filter(s -> s.userId().equals(claimedTurnUserId))
                .findFirst()
                .orElse(null); // stale claim on a superseded record — treat as unclaimed
    }

    /** Signers whose signature has not been accepted yet, in slot order. */
    public List<WorkflowSignerRecord> pendingSigners() {
        return signers.stream().filter(s -> !s.signed()).toList();
    }

    /** Whether {@code userId} holds a pending signer slot. */
    public boolean isEligibleSigner(String userId) {
        return pendingSigners().stream().anyMatch(s -> s.userId().equals(userId));
    }

    /**
     * Whether {@code userId} may submit a signature right now: they must hold
     * the turn — for SEQUENTIAL that means being the first pending signer,
     * for FLEXIBLE having the (unexpired) claim.
     */
    public boolean maySubmit(String userId) {
        WorkflowSignerRecord current = currentSigner();
        return current != null && current.userId().equals(userId);
    }

    /** The current turn claim holder, or {@code null}. */
    public String claimedTurnUserId() {
        return currentSigner() == null ? null : claimedTurnUserId;
    }

    /**
     * Reserves the signing turn for {@code userId}.
     *
     * <ul>
     *   <li>SEQUENTIAL: legal only for the first pending signer; a no-op
     *       formality that still verifies the caller's turn.</li>
     *   <li>FLEXIBLE: atomically reserves the single turn. Re-claiming your
     *       own (unexpired) claim refreshes it. Superseding another signer's
     *       claim is legal only once that claim has expired.</li>
     * </ul>
     *
     * @return the signer record of the turn holder after the claim
     * @throws IllegalStateException when the caller holds no pending slot, or
     *         another eligible signer holds an unexpired claim
     */
    public WorkflowSignerRecord claimTurn(String userId) {
        WorkflowSignerRecord claimer = signers.stream()
                .filter(s -> !s.signed() && s.userId().equals(userId))
                .findFirst()
                .orElse(null);
        if (claimer == null) {
            throw new IllegalStateException(
                    userId + " is not a pending signer of this workflow");
        }
        WorkflowSignerRecord holder = currentSigner();
        if (holder == claimer) {
            claimedTurnAt = Instant.now(); // refresh own claim
            return claimer;
        }
        if (holder != null) {
            throw new IllegalStateException("The signing turn is held by "
                    + holder.userId());
        }
        claimedTurnUserId = userId;
        claimedTurnAt = Instant.now();
        return claimer;
    }

    /** Releases any FLEXIBLE turn claim (used on cancel / completion). */
    public void releaseTurn() {
        claimedTurnUserId = null;
        claimedTurnAt = null;
    }

    private boolean isClaimExpired() {
        return claimedTurnAt == null
                || claimedTurnAt.plus(CLAIM_TIMEOUT).isBefore(Instant.now());
    }

    public boolean allSigned() {
        return signers.stream().allMatch(WorkflowSignerRecord::signed);
    }

    /** Whether every signer slot has a certificate bound (precondition for revision 0). */
    public boolean allBound() {
        return signers.stream().allMatch(WorkflowSignerRecord::isBound);
    }

    /** Whether the current revision's number equals {@code revisionNumber}. */
    public boolean expectsRevision(int revisionNumber) {
        return currentRevision().revisionNumber() == revisionNumber;
    }

    /** State-machine-guarded transition; throws on illegal transitions. */
    public void transition(WorkflowState target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Illegal workflow transition " + status + " → " + target);
        }
        status = target;
    }

    /**
     * Marks {@code userId}'s slot signed and — for SEQUENTIAL workflows —
     * returns the NEXT signer (or {@code null} when that was the last one).
     * FLEXIBLE workflows have no predefined next signer: returns {@code null}
     * and releases the claimed turn.
     */
    public WorkflowSignerRecord markSignedByUser(String userId, String signingOperationId) {
        return markSignedByUser(userId, signingOperationId, null);
    }

    /**
     * As above, additionally recording the accepted visible-signature
     * placement ({@code null} = invisible signature) on the signer slot.
     */
    public WorkflowSignerRecord markSignedByUser(String userId,
                                                 String signingOperationId,
                                                 SignaturePlacement acceptedPlacement) {
        WorkflowSignerRecord slot = signers.stream()
                .filter(s -> !s.signed() && s.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        userId + " holds no pending signer slot"));
        WorkflowSignerRecord signed = slot.asSigned(Instant.now(), signingOperationId,
                acceptedPlacement);
        signers.set(signers.indexOf(slot), signed);
        releaseTurn();
        return policy == WorkflowSigningPolicy.SEQUENTIAL
                ? signers.stream().filter(s -> !s.signed()).findFirst().orElse(null)
                : null;
    }

    /**
     * Binds {@code fingerprint} to the pending slot of {@code userId}
     * (certificate enrollment, §14/§17 of the task spec). Legal only while
     * the workflow is DRAFT — the service enforces that.
     *
     * @return the updated signer record
     */
    public WorkflowSignerRecord bindCertificate(String userId, String fingerprint) {
        WorkflowSignerRecord slot = signers.stream()
                .filter(s -> s.userId().equals(userId) && !s.signed())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        userId + " holds no pending signer slot"));
        WorkflowSignerRecord bound = slot.asBound(fingerprint);
        signers.set(signers.indexOf(slot), bound);
        return bound;
    }

    /** Appends one accepted revision (must be exactly the next number). */
    public void appendRevision(DocumentRevisionRecord revision) {
        if (revision.revisionNumber() != revisions.size()) {
            throw new IllegalArgumentException("revision number must be " + revisions.size());
        }
        revisions.add(revision);
    }

    /**
     * Replaces the DRAFT placeholder revision 0 with the real prepared
     * document record (used once, when the last signer binds their
     * certificate and the manifest-bearing document is prepared).
     */
    public void replaceRevision0(DocumentRevisionRecord revision) {
        if (revisions.size() != 1 || revisions.get(0).revisionNumber() != 0
                || revision.revisionNumber() != 0) {
            throw new IllegalArgumentException(
                "replaceRevision0 is only valid for a DRAFT workflow's revision 0");
        }
        revisions.set(0, revision);
    }

    /** Appends one audit event with server clock and monotonic sequence. */
    public void appendAudit(AuditEventType type, String actor, String detail) {
        audit.add(new AuditEventRecord(audit.size() + 1L, type, actor, detail, Instant.now()));
    }

    public void block(String reason) {
        this.blockedReason = reason;
    }

    /** Persistable snapshot of the aggregate (claim and creator included). */
    public Snapshot snapshot() {
        return new Snapshot(workflowId, documentName, policy, status, blockedReason,
                claimedTurnUserId, claimedTurnAt, createdBy,
                List.copyOf(signers), List.copyOf(revisions), List.copyOf(audit));
    }

    /** Persistable snapshot of the aggregate. */
    public record Snapshot(
            String workflowId,
            String documentName,
            WorkflowSigningPolicy policy,
            WorkflowState status,
            String blockedReason,
            String claimedTurnUserId,
            Instant claimedTurnAt,
            String createdBy,
            List<WorkflowSignerRecord> signers,
            List<DocumentRevisionRecord> revisions,
            List<AuditEventRecord> audit) {
    }
}

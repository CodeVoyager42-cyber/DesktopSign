package com.example.signer.server.workflow;

import com.example.signer.signing.SignaturePlacement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The owning aggregate of a server-coordinated signing workflow.
 *
 * <p>All state changes funnel through {@link #transition},
 * {@link #markSignedByUser}, {@link #appendRevision} and
 * {@link #appendAudit}, keeping the invariants — contiguous revision chain,
 * exactly one signer slot — in one place. The aggregate performs NO
 * persistence and NO locking; the service serialises access with the
 * per-workflow lock.
 *
 * <p><b>One document, one signer.</b> SignFlow signs the document of the
 * account that uploaded it: a workflow has exactly one signer slot, and it
 * belongs to its creator. There is no signing order to negotiate and no turn
 * to hand over or claim — the slot's owner holds it from creation until the
 * signature is accepted, which is also the moment the workflow completes.
 */
public final class SigningWorkflowAggregate {

    /**
     * Version of an aggregate that has never been persisted. Stores refuse
     * to save a NEW aggregate over an existing workflow, and refuse to save
     * a persisted aggregate whose version no longer matches the stored one
     * (optimistic concurrency: a stale copy can never overwrite newer state).
     */
    public static final long NEW = -1L;

    private final String workflowId;
    private final String documentName;
    private final List<WorkflowSignerRecord> signers;
    private final List<DocumentRevisionRecord> revisions;
    private final List<AuditEventRecord> audit;

    private WorkflowState status;
    private String blockedReason;
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
                                     List<WorkflowSignerRecord> signers,
                                     List<DocumentRevisionRecord> revisions,
                                     List<AuditEventRecord> audit,
                                     WorkflowState status,
                                     String blockedReason,
                                     String createdBy) {
        this.workflowId = workflowId;
        this.documentName = documentName;
        this.signers = new ArrayList<>(signers);
        this.revisions = new ArrayList<>(revisions);
        this.audit = new ArrayList<>(audit);
        this.status = status;
        this.blockedReason = blockedReason;
        this.createdBy = createdBy;
    }

    /** Rehydrates an aggregate from storage; validates internal consistency. */
    public static SigningWorkflowAggregate rehydrate(String workflowId,
                                                     String documentName,
                                                     List<WorkflowSignerRecord> signers,
                                                     List<DocumentRevisionRecord> revisions,
                                                     List<AuditEventRecord> audit,
                                                     WorkflowState status,
                                                     String blockedReason) {
        return rehydrate(workflowId, documentName, signers, revisions, audit,
                status, blockedReason, null);
    }

    /**
     * Rehydration by a {@link WorkflowStore}: the stored state plus the
     * stored {@code version} the copy was read at. Every audit event passed
     * in is considered already persisted.
     */
    public static SigningWorkflowAggregate rehydrateStored(String workflowId,
                                                           String documentName,
                                                           List<WorkflowSignerRecord> signers,
                                                           List<DocumentRevisionRecord> revisions,
                                                           List<AuditEventRecord> audit,
                                                           WorkflowState status,
                                                           String blockedReason,
                                                           String createdBy,
                                                           long version) {
        SigningWorkflowAggregate wf = rehydrate(workflowId, documentName, signers,
                revisions, audit, status, blockedReason, createdBy);
        wf.version = version;
        return wf;
    }

    /** Full rehydration including the creator. */
    public static SigningWorkflowAggregate rehydrate(String workflowId,
                                                     String documentName,
                                                     List<WorkflowSignerRecord> signers,
                                                     List<DocumentRevisionRecord> revisions,
                                                     List<AuditEventRecord> audit,
                                                     WorkflowState status,
                                                     String blockedReason,
                                                     String createdBy) {
        if (signers == null || signers.isEmpty()) {
            throw new IllegalArgumentException("a workflow needs its signer");
        }
        // One signer, and it is slot 1. A stored workflow with more than one
        // slot predates single-signer signing and cannot be represented here;
        // failing loudly beats silently dropping somebody's signature.
        if (signers.size() != 1) {
            throw new IllegalArgumentException(
                    "a workflow has exactly one signer, but " + workflowId
                            + " has " + signers.size());
        }
        if (signers.get(0).order() != 1) {
            throw new IllegalArgumentException("the signer slot must be order 1");
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
                signers, revisions, audit == null ? List.of() : audit, status,
                blockedReason, createdBy);
        wf.persistedAuditCount = wf.audit.size();
        return wf;
    }

    /**
     * Creates a fresh DRAFT workflow. DRAFT = no real revision 0 yet: the
     * workflow stays in DRAFT until its signer has bound a certificate
     * (revision 0 embeds the manifest that names the bound identity, so it
     * can only be written once that is known).
     */
    public static SigningWorkflowAggregate create(String workflowId,
                                                  String documentName,
                                                  WorkflowSignerRecord signer,
                                                  String createdBy) {
        DocumentRevisionRecord zero = new DocumentRevisionRecord(0, -1,
                "0".repeat(64), 0, Instant.now(), "system");
        return rehydrate(workflowId, documentName, List.of(signer),
                List.of(zero), List.of(), WorkflowState.DRAFT, null, createdBy);
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
        SigningWorkflowAggregate copy = rehydrate(workflowId, documentName,
                signers, revisions, List.of(), status, blockedReason, createdBy);
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

    /** The authenticated user who created the workflow — also its signer. */
    public String createdBy() {
        return createdBy;
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

    /** The one signer slot of this workflow. */
    public WorkflowSignerRecord signer() {
        return signers.get(0);
    }

    public List<DocumentRevisionRecord> revisions() {
        return List.copyOf(revisions);
    }

    public List<AuditEventRecord> audit() {
        return List.copyOf(audit);
    }

    /** The newest accepted revision — what the signer must download. */
    public DocumentRevisionRecord currentRevision() {
        return revisions.get(revisions.size() - 1);
    }

    /**
     * The signer who holds the signing turn, or {@code null} once the
     * signature has been accepted. With one slot there is nothing to choose:
     * it is the slot, until it is signed.
     */
    public WorkflowSignerRecord currentSigner() {
        WorkflowSignerRecord slot = signers.get(0);
        return slot.signed() ? null : slot;
    }

    /**
     * Whether {@code userId} may submit a signature right now: they must own
     * the one unsigned slot.
     */
    public boolean maySubmit(String userId) {
        WorkflowSignerRecord current = currentSigner();
        return current != null && current.userId().equals(userId);
    }

    public boolean allSigned() {
        return signers.get(0).signed();
    }

    /** Whether the signer slot has a certificate bound (precondition for revision 0). */
    public boolean allBound() {
        return signers.get(0).isBound();
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

    /** Marks {@code userId}'s slot signed. */
    public void markSignedByUser(String userId, String signingOperationId) {
        markSignedByUser(userId, signingOperationId, null);
    }

    /**
     * As above, additionally recording the accepted visible-signature
     * placement ({@code null} = invisible signature) on the signer slot.
     */
    public void markSignedByUser(String userId, String signingOperationId,
                                 SignaturePlacement acceptedPlacement) {
        WorkflowSignerRecord slot = signers.get(0);
        if (slot.signed() || !slot.userId().equals(userId)) {
            throw new IllegalStateException(
                    userId + " holds no pending signer slot");
        }
        signers.set(0, slot.asSigned(Instant.now(), signingOperationId,
                acceptedPlacement));
    }

    /**
     * Binds {@code fingerprint} to the pending slot of {@code userId}
     * (certificate enrollment on the signer's own machine). Legal only while
     * the workflow is DRAFT — the service enforces that.
     *
     * @return the updated signer record
     */
    public WorkflowSignerRecord bindCertificate(String userId, String fingerprint) {
        WorkflowSignerRecord slot = signers.get(0);
        if (slot.signed() || !slot.userId().equals(userId)) {
            throw new IllegalStateException(
                    userId + " holds no pending signer slot");
        }
        if (slot.isBound()) {
            throw new IllegalStateException(userId + "'s slot already has a certificate");
        }
        WorkflowSignerRecord bound = slot.asBound(fingerprint);
        signers.set(0, bound);
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
     * document record (used once, when the signer binds their certificate
     * and the manifest-bearing document is prepared).
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

    /** Persistable snapshot of the aggregate. */
    public Snapshot snapshot() {
        return new Snapshot(workflowId, documentName, status, blockedReason,
                createdBy, List.copyOf(signers), List.copyOf(revisions),
                List.copyOf(audit));
    }

    /** Persistable snapshot of the aggregate. */
    public record Snapshot(
            String workflowId,
            String documentName,
            WorkflowState status,
            String blockedReason,
            String createdBy,
            List<WorkflowSignerRecord> signers,
            List<DocumentRevisionRecord> revisions,
            List<AuditEventRecord> audit) {
    }
}

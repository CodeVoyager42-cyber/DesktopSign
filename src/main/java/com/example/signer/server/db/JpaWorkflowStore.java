package com.example.signer.server.db;

import com.example.signer.server.AuditEventType;
import com.example.signer.workflow.WorkflowSigningPolicy;
import java.math.BigDecimal;
import com.example.signer.server.AuditEventRecord;
import com.example.signer.server.DocumentRevisionRecord;
import com.example.signer.signing.SignaturePlacement;
import com.example.signer.server.SigningWorkflowAggregate;
import com.example.signer.server.WorkflowSignerRecord;
import com.example.signer.server.WorkflowState;
import com.example.signer.server.WorkflowConflictException;
import com.example.signer.server.WorkflowRegressionGuard;
import com.example.signer.server.WorkflowStore;
import com.example.signer.server.db.entity.AuditEventEntity;
import com.example.signer.server.db.entity.DocumentVersionEntity;
import com.example.signer.server.db.entity.WorkflowEntity;
import com.example.signer.server.db.entity.WorkflowSignerEntity;
import com.example.signer.server.db.repository.AuditEventRepository;
import com.example.signer.server.db.repository.DocumentVersionRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.db.repository.WorkflowSignerRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL-backed {@link WorkflowStore} (brief Phase 1): the aggregate
 * snapshot maps onto {@code workflows} + {@code workflow_signers} +
 * {@code document_versions} + {@code audit_events}; PDF bytes stay on the
 * filesystem via {@link DocumentFileStorage} under the identical layout the
 * file store always used.
 *
 * <p>{@link #save} is transactional and append-only where the domain is:
 * existing signers are updated in place, revisions and audit events are only
 * ever appended (their sequence numbers are the natural keys). The service
 * layer serialises mutations with the per-workflow lock before calling.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class JpaWorkflowStore implements WorkflowStore {

    private final WorkflowRepository workflows;
    private final WorkflowSignerRepository signers;
    private final DocumentVersionRepository versions;
    private final AuditEventRepository auditEvents;
    private final DocumentFileStorage blobs;
    private final AuditLog auditLog;

    @PersistenceContext
    private EntityManager entityManager;

    /** One lock object per workflow id, retained for the server's lifetime. */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public JpaWorkflowStore(WorkflowRepository workflows,
                            WorkflowSignerRepository signers,
                            DocumentVersionRepository versions,
                            AuditEventRepository auditEvents,
                            DocumentFileStorage blobs,
                            AuditLog auditLog) {
        this.workflows = workflows;
        this.signers = signers;
        this.versions = versions;
        this.auditEvents = auditEvents;
        this.blobs = blobs;
        this.auditLog = auditLog;
    }

    @Override
    public Object lockFor(String workflowId) {
        return locks.computeIfAbsent(workflowId, id -> new Object());
    }

    @Override
    public boolean exists(String workflowId) {
        return workflows.existsByWorkflowId(workflowId);
    }

    @Override
    public List<String> listWorkflowIds() {
        return workflows.findAllWorkflowIds();
    }

    @Override
    public List<String> listWorkflowIdsForSigner(String userId) {
        return workflows.findWorkflowIdsBySigner(userId);
    }

    @Override
    public List<String> listWorkflowIdsCreatedBy(String userId) {
        return workflows.findWorkflowIdsByCreator(userId);
    }

    /**
     * Loads the aggregate in ONE read-only repeatable-read transaction, so
     * the workflow row, its version and its child rows come from the same
     * database snapshot (no half-applied concurrent save can be observed).
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SigningWorkflowAggregate load(String workflowId) {
        WorkflowEntity wf = workflows.findByWorkflowId(workflowId).orElse(null);
        if (wf == null) {
            return null;
        }
        return toAggregate(wf);
    }

    private SigningWorkflowAggregate toAggregate(WorkflowEntity wf) {
        List<WorkflowSignerRecord> signers = this.signers
                .findByWorkflowIdOrderBySignerOrder(wf.getId()).stream()
                .map(JpaWorkflowStore::toRecord)
                .toList();
        List<DocumentRevisionRecord> revisions = versions
                .findByWorkflowIdOrderByRevisionNumber(wf.getId()).stream()
                .map(JpaWorkflowStore::toRecord)
                .toList();
        List<AuditEventRecord> audit = auditEvents
                .findByWorkflowIdOrderByEventSeq(wf.getId()).stream()
                .map(JpaWorkflowStore::toRecord)
                .toList();
        return SigningWorkflowAggregate.rehydrateStored(wf.getWorkflowId(),
                wf.getDocumentName(),
                WorkflowSigningPolicy.parse(wf.getPolicy()),
                signers, revisions, audit,
                WorkflowState.valueOf(wf.getStatus()),
                wf.getBlockedReason(),
                wf.getClaimedTurnUsername(),
                wf.getClaimedTurnAt(),
                wf.getCreatedBy(),
                wf.getVersion() == null ? 0L : wf.getVersion());
    }

    /**
     * Persists the aggregate with optimistic concurrency control.
     *
     * <ol>
     *   <li>{@code SELECT ... FOR UPDATE} on the workflow row: concurrent
     *       savers of the same workflow — in this process or another one —
     *       queue here until this transaction commits.</li>
     *   <li>The stored {@code @Version} must equal the version the aggregate
     *       was loaded at; otherwise the caller's copy is stale and the save
     *       is refused ({@link WorkflowConflictException}, HTTP 409).</li>
     *   <li>{@link WorkflowRegressionGuard}: a save can never leave a
     *       terminal state, un-sign a signer or drop a revision.</li>
     *   <li>Rows are written, new audit events get their sequence numbers
     *       from the database under the row lock, the version is bumped and
     *       everything is flushed before returning, so constraint or version
     *       failures surface here (rolled back) rather than at commit.</li>
     * </ol>
     */
    @Override
    @Transactional
    public void save(SigningWorkflowAggregate aggregate) {
        try {
            doSave(aggregate);
        } catch (OptimisticLockException | OptimisticLockingFailureException e) {
            throw new WorkflowConflictException("Workflow " + aggregate.workflowId()
                    + " was changed by another request", e);
        }
    }

    private void doSave(SigningWorkflowAggregate aggregate) {
        SigningWorkflowAggregate.Snapshot snap = aggregate.snapshot();

        WorkflowEntity wf = workflows.lockByWorkflowId(aggregate.workflowId()).orElse(null);
        boolean created = wf == null;
        if (created) {
            if (!aggregate.isNew()) {
                throw new WorkflowConflictException("Workflow "
                        + aggregate.workflowId() + " no longer exists");
            }
            wf = new WorkflowEntity();
            wf.setWorkflowId(aggregate.workflowId());
        } else {
            if (aggregate.isNew()) {
                throw new WorkflowConflictException("Workflow "
                        + aggregate.workflowId() + " already exists");
            }
            long storedVersion = wf.getVersion() == null ? 0L : wf.getVersion();
            if (storedVersion != aggregate.version()) {
                throw new WorkflowConflictException("Workflow "
                        + aggregate.workflowId() + " was changed by another request"
                        + " (stored version " + storedVersion + ", loaded version "
                        + aggregate.version() + ")");
            }
            WorkflowRegressionGuard.check(toAggregate(wf), aggregate);
            wf.touch(); // always a versioned UPDATE, even if only child rows change
        }
        wf.setDocumentName(snap.documentName());
        wf.setPolicy(snap.policy().name());
        wf.setStatus(snap.status().name());
        wf.setBlockedReason(snap.blockedReason());
        wf.setClaimedTurnUsername(snap.claimedTurnUserId());
        wf.setClaimedTurnAt(snap.claimedTurnAt());
        wf.setCreatedBy(snap.createdBy());
        wf = workflows.save(wf);

        // Signers: update in place by signing order, insert the new ones.
        Map<Integer, WorkflowSignerEntity> existing = new HashMap<>();
        for (WorkflowSignerEntity e : signers.findByWorkflowIdOrderBySignerOrder(wf.getId())) {
            existing.put(e.getSignerOrder(), e);
        }
        for (WorkflowSignerRecord s : snap.signers()) {
            WorkflowSignerEntity e = existing.get(s.order());
            if (e == null) {
                e = new WorkflowSignerEntity();
                e.setWorkflowId(wf.getId());
                e.setSignerOrder(s.order());
            }
            e.setUsername(s.userId());
            e.setCertificateSha256(s.certificateSha256());
            e.setBoundAt(s.boundAt());
            e.setSigned(s.signed());
            e.setSignedAt(s.signedAt());
            e.setSigningOperationId(s.signingOperationId());
            if (s.placement() != null) {
                e.setSignaturePage(s.placement().pageIndex());
                e.setSignatureX(BigDecimal.valueOf(s.placement().x()));
                e.setSignatureY(BigDecimal.valueOf(s.placement().y()));
                e.setSignatureWidth(BigDecimal.valueOf(s.placement().width()));
                e.setSignatureHeight(BigDecimal.valueOf(s.placement().height()));
            }
            signers.save(e);
        }

        // Document versions: append-only by revision number — except the
        // legal rewrite path replaceRevision0 (DRAFT placeholder → prepared
        // document), so an existing row is updated when the record differs.
        Map<Integer, DocumentVersionEntity> existingVersions = new HashMap<>();
        for (DocumentVersionEntity e : versions.findByWorkflowIdOrderByRevisionNumber(wf.getId())) {
            existingVersions.put(e.getRevisionNumber(), e);
        }
        for (DocumentRevisionRecord r : snap.revisions()) {
            DocumentVersionEntity e = existingVersions.get(r.revisionNumber());
            if (e == null) {
                e = new DocumentVersionEntity();
                e.setWorkflowId(wf.getId());
                e.setRevisionNumber(r.revisionNumber());
            } else if (r.sha256().equals(e.getSha256())
                    && r.sizeBytes() == e.getSizeBytes()) {
                continue; // unchanged
            }
            e.setParentRevision(r.parentRevision());
            e.setSha256(r.sha256());
            e.setSizeBytes(r.sizeBytes());
            e.setCreatedBy(r.createdBy());
            versions.save(e);
        }

        // Audit: only the events appended since the load are inserted, and
        // their sequence numbers come from the database under the row lock
        // (the in-memory numbers may collide with events other writers —
        // invitations, registration, the expiry sweeper — committed since).
        if (!created) {
            entityManager.flush(); // make the row lock + versioned update visible first
        }
        for (AuditEventRecord a : aggregate.unsavedAuditEvents()) {
            auditLog.appendLocked(wf.getId(), a.eventType().name(), a.actor(),
                    a.detail(), a.occurredAt());
        }
        entityManager.flush();
        aggregate.markPersisted(wf.getVersion() == null ? 0L : wf.getVersion());
    }

    @Override
    public void discardUncommittedRevision(String workflowId, int number) {
        blobs.deleteRevision(workflowId, number);
    }

    // ── blob operations: identical file layout as the file-based store ──

    @Override
    public void stageOriginal(String workflowId, byte[] bytes) {
        blobs.stageOriginal(workflowId, bytes);
    }

    @Override
    public byte[] readStagedOriginal(String workflowId) {
        return blobs.readStagedOriginal(workflowId);
    }

    @Override
    public void writeRevision(String workflowId, int number, byte[] bytes) {
        blobs.writeRevision(workflowId, number, bytes);
    }

    @Override
    public byte[] readRevision(String workflowId, int number) {
        return blobs.readRevision(workflowId, number);
    }

    @Override
    public boolean existsRevision(String workflowId, int number) {
        return blobs.existsRevision(workflowId, number);
    }

    // ── mapping helpers ─────────────────────────────────────────────────

    private static WorkflowSignerRecord toRecord(WorkflowSignerEntity e) {
        SignaturePlacement placement = e.getSignaturePage() == null ? null
                : new SignaturePlacement(e.getSignaturePage(),
                        e.getSignatureX().floatValue(),
                        e.getSignatureY().floatValue(),
                        e.getSignatureWidth().floatValue(),
                        e.getSignatureHeight().floatValue());
        return new WorkflowSignerRecord(e.getSignerOrder(), e.getUsername(),
                e.getCertificateSha256(), e.getBoundAt(), e.isSigned(),
                e.getSignedAt(), e.getSigningOperationId(), placement);
    }

    private static DocumentRevisionRecord toRecord(DocumentVersionEntity e) {
        return new DocumentRevisionRecord(e.getRevisionNumber(),
                e.getParentRevision(), e.getSha256(), e.getSizeBytes(),
                e.getCreatedAt(), e.getCreatedBy());
    }

    private static AuditEventRecord toRecord(AuditEventEntity e) {
        return new AuditEventRecord(e.getEventSeq(),
                AuditEventType.valueOf(e.getEventType()),
                e.getActor(), e.getDetail(), e.getOccurredAt());
    }
}

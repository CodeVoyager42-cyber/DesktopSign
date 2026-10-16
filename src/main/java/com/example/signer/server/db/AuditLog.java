package com.example.signer.server.db;

import com.example.signer.server.workflow.AuditEventType;
import com.example.signer.server.db.entity.AuditEventEntity;
import com.example.signer.server.db.entity.WorkflowEntity;
import com.example.signer.server.db.repository.AuditEventRepository;
import com.example.signer.server.db.repository.WorkflowRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The ONE writer of workflow audit events in database mode.
 *
 * <p>Sequence numbers are assigned as {@code max(event_seq) + 1} while the
 * caller's transaction holds the workflow row lock
 * ({@code SELECT ... FOR UPDATE}, {@link WorkflowRepository#lockById}). All
 * audit writers — the aggregate store, invitations, registration and the
 * expiry sweeper — take that same lock first, so two writers can never
 * compute the same number, no event is dropped, and the order of the trail
 * is the commit order. The {@code uq_audit_workflow_seq} constraint stays
 * as the database-level backstop.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class AuditLog {

    private final AuditEventRepository events;
    private final WorkflowRepository workflows;

    public AuditLog(AuditEventRepository events, WorkflowRepository workflows) {
        this.events = events;
        this.workflows = workflows;
    }

    /**
     * Locks the workflow row (if this transaction does not hold it yet) and
     * appends one event. Must run inside a transaction.
     *
     * @return the locked workflow row (callers may keep using it)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkflowEntity append(Long workflowDbId, AuditEventType type,
                                 String actor, String detail) {
        WorkflowEntity locked = workflows.lockById(workflowDbId)
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow row " + workflowDbId + " does not exist"));
        appendLocked(workflowDbId, type.name(), actor, detail, Instant.now());
        return locked;
    }

    /**
     * Appends one event; the caller's transaction MUST already hold the
     * workflow row lock.
     *
     * @return the sequence number assigned to the event
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long appendLocked(Long workflowDbId, String type, String actor,
                             String detail, Instant occurredAt) {
        long next = events.maxSequence(workflowDbId) + 1;
        AuditEventEntity event = new AuditEventEntity();
        event.setWorkflowId(workflowDbId);
        event.setEventSeq(next);
        event.setEventType(type);
        event.setActor(actor == null || actor.isBlank() ? "system" : actor);
        event.setDetail(detail);
        event.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
        events.save(event);
        return next;
    }
}

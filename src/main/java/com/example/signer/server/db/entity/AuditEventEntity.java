package com.example.signer.server.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One persistent audit event (brief §37) — relational counterpart of
 * {@code AuditEventRecord}. Append-only, unique per (workflow, sequence).
 * {@code actor} is provenance text (a username or {@code "system"}); no
 * secrets are ever recorded in {@code detail}.
 */
@Entity
@Table(name = "audit_events", uniqueConstraints = {
        @UniqueConstraint(name = "uq_audit_workflow_seq",
                columnNames = {"workflow_id", "event_seq"})})
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "event_seq", nullable = false)
    private long eventSeq;

    /** {@code AuditEventType} name. */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public AuditEventEntity() {
    }

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public long getEventSeq() {
        return eventSeq;
    }

    public void setEventSeq(long eventSeq) {
        this.eventSeq = eventSeq;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}

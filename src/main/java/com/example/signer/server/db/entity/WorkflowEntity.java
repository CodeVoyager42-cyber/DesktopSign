package com.example.signer.server.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A server-coordinated signing workflow (brief §20). Scalar state only —
 * signers, document versions and audit events are separate tables keyed by
 * {@link #getId()}, mirroring the {@code SigningWorkflowAggregate.Snapshot}.
 */
@Entity
@Table(name = "workflows")
public class WorkflowEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public API id, e.g. {@code WF-1790898023826}. */
    @Column(name = "workflow_id", nullable = false, unique = true, length = 64)
    private String workflowId;

    @Column(name = "document_name", nullable = false, length = 500)
    private String documentName;

    /** SEQUENTIAL | FLEXIBLE. */
    @Column(nullable = false, length = 20)
    private String policy;

    /** {@code WorkflowState} name. */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "blocked_reason", columnDefinition = "text")
    private String blockedReason;

    /** FLEXIBLE: who currently holds the turn (null when unclaimed). */
    @Column(name = "claimed_turn_username", length = 100)
    private String claimedTurnUsername;

    @Column(name = "claimed_turn_at")
    private Instant claimedTurnAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic-lock version (JPA {@code @Version}). Incremented on every
     * aggregate save; a write based on an older version fails instead of
     * silently overwriting newer workflow state.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public WorkflowEntity() {
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** The optimistic-lock version ({@code null} until first persisted). */
    public Long getVersion() {
        return version;
    }

    /**
     * Marks the row modified so the save increments {@link #getVersion()}
     * even when only child rows (signers, revisions, audit) changed.
     */
    public void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public String getClaimedTurnUsername() {
        return claimedTurnUsername;
    }

    public void setClaimedTurnUsername(String claimedTurnUsername) {
        this.claimedTurnUsername = claimedTurnUsername;
    }

    public Instant getClaimedTurnAt() {
        return claimedTurnAt;
    }

    public void setClaimedTurnAt(Instant claimedTurnAt) {
        this.claimedTurnAt = claimedTurnAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

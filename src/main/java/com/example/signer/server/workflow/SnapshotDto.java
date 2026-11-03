package com.example.signer.server.workflow;

import java.util.List;

/**
 * JSON DTO for persisting a workflow aggregate snapshot ({@code state.json}).
 * {@code version} is the optimistic-concurrency version (absent in snapshots
 * written before versioning; read as 0).
 */
public record SnapshotDto(
        String documentName,
        WorkflowState status,
        String blockedReason,
        String createdBy,
        List<WorkflowSignerRecord> signers,
        List<DocumentRevisionRecord> revisions,
        List<AuditEventRecord> audit,
        Long version) {

    /** Snapshot written before versioning existed (version 0 on load). */
    public SnapshotDto(String documentName, WorkflowState status,
                       String blockedReason, String createdBy,
                       List<WorkflowSignerRecord> signers,
                       List<DocumentRevisionRecord> revisions,
                       List<AuditEventRecord> audit) {
        this(documentName, status, blockedReason, createdBy, signers,
                revisions, audit, null);
    }
}

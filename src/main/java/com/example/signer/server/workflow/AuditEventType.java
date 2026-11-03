package com.example.signer.server.workflow;

/** Event types of the server-side workflow audit trail. */
public enum AuditEventType {
    WORKFLOW_CREATED,
    /**
     * The workflow was created but cannot be prepared yet: its slot has no
     * certificate bound, so it stays DRAFT until the signer registers one
     * from the machine their token is plugged into.
     */
    CERTIFICATE_PENDING,
    WORKFLOW_PREPARED,
    CERTIFICATE_BOUND,
    SIGNATURE_SUBMITTED,
    SIGNATURE_PLACEMENT_RECORDED,
    SIGNATURE_VALIDATED,
    SIGNATURE_VALIDATION_FAILED,
    REVISION_CREATED,
    SIGNER_COMPLETED,
    WORKFLOW_COMPLETED,
    STALE_REVISION_REJECTED,
    CERTIFICATE_MISMATCH_REJECTED,
    DUPLICATE_OPERATION_IGNORED,
    WORKFLOW_CANCELLED,
    WORKFLOW_BLOCKED,
    /** Phase B: a desktop task session was opened with a website launch code. */
    DESKTOP_SESSION_OPENED,
    /** Phase B: the user revoked a desktop task session ("this wasn't me"). */
    DESKTOP_SESSION_REVOKED
}

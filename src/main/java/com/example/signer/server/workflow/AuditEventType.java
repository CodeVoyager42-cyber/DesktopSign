package com.example.signer.server.workflow;

/** Event types of the server-side workflow audit trail. */
public enum AuditEventType {
    INVITATION_CREATED,
    INVITATION_SENT,
    INVITATION_ACCEPTED,
    INVITATION_REVOKED,
    INVITATION_EXPIRED,
    /** The invitation exists but the email transport reported failure (§37). */
    EMAIL_DELIVERY_FAILED,
    /** A scoped signing session was created from a claimed invitation (§46). */
    SIGNING_SESSION_CREATED,
    SIGNER_REGISTERED,
    WORKFLOW_CREATED,
    WORKFLOW_PREPARED,
    CERTIFICATE_BOUND,
    SIGNING_TURN_CLAIMED,
    SIGNING_TURN_RELEASED,
    SIGNATURE_SUBMITTED,
    SIGNATURE_PLACEMENT_RECORDED,
    SIGNATURE_VALIDATED,
    SIGNATURE_VALIDATION_FAILED,
    REVISION_CREATED,
    SIGNER_COMPLETED,
    NEXT_SIGNER_ACTIVATED,
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

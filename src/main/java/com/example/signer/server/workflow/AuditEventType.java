package com.example.signer.server.workflow;

/** Event types of the server-side workflow audit trail. */
public enum AuditEventType {
    /**
     * @deprecated <b>Historical only — never delete.</b> The invitation
     *     workflow that wrote these five is removed, so nothing appends them
     *     any more. Rows bearing them record invitations that really were
     *     created, sent, accepted, revoked or expired, and a signature
     *     obtained through one is only explicable with them: delete the
     *     values and a stored trail stops deserialising, which is the audit
     *     trail failing in exactly the dispute it exists to settle.
     */
    @Deprecated
    INVITATION_CREATED,
    /** @deprecated Historical only; see {@link #INVITATION_CREATED}. */
    @Deprecated
    INVITATION_SENT,
    /** @deprecated Historical only; see {@link #INVITATION_CREATED}. */
    @Deprecated
    INVITATION_ACCEPTED,
    /** @deprecated Historical only; see {@link #INVITATION_CREATED}. */
    @Deprecated
    INVITATION_REVOKED,
    /** @deprecated Historical only; see {@link #INVITATION_CREATED}. */
    @Deprecated
    INVITATION_EXPIRED,
    /** The invitation exists but the email transport reported failure (§37). */
    EMAIL_DELIVERY_FAILED,
    /** A scoped signing session was created from a claimed invitation (§46). */
    SIGNING_SESSION_CREATED,
    SIGNER_REGISTERED,
    WORKFLOW_CREATED,
    /**
     * The workflow was created but cannot be prepared yet: at least one slot
     * has no certificate bound, so it stays DRAFT until every signer has
     * registered theirs from their own machine.
     *
     * <p>Recorded as {@code WORKFLOW_CREATED} until this type existed, which
     * made a trail with two "created" events in it — and any reader that
     * shows an event's type rather than its prose detail (the website's
     * status timeline does, deliberately) could only say "Created" twice.
     */
    CERTIFICATES_PENDING,
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
    /** A desktop task session was opened with a website launch code. */
    DESKTOP_SESSION_OPENED,
    /** The user revoked a desktop task session ("this wasn't me"). */
    DESKTOP_SESSION_REVOKED
}

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
    /**
     * A desktop task session was opened with a website launch code.
     *
     * @deprecated <b>Historical only — never delete.</b> The launch-code path
     *     that wrote this is removed, so nothing appends it any more, but rows
     *     bearing it record sessions that really happened and are evidence in
     *     exactly the disputes this product's audit trail exists to settle.
     *     The value stays so those rows keep deserialising into a trail that
     *     can still be read and verified.
     */
    @Deprecated
    DESKTOP_SESSION_OPENED,
    /**
     * The user revoked a desktop task session ("this wasn't me").
     *
     * @deprecated Historical only — never delete; see
     *     {@link #DESKTOP_SESSION_OPENED}.
     */
    @Deprecated
    DESKTOP_SESSION_REVOKED
}

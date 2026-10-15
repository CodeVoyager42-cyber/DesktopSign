package com.example.signer.server;

/**
 * Explicit state machine of a server-coordinated signing workflow.
 *
 * <p>The server is authoritative: clients may read state but never set it.
 * Transitions are validated by {@link #canTransitionTo}; the service layer
 * rejects any transition not listed here (fail closed).
 *
 * <pre>
 * DRAFT ──► READY_FOR_SIGNING ──► WAITING_FOR_SIGNER ──► SIGNATURE_SUBMITTED
 *               │                       │        ▲            │
 *               │                       │        └────────────┤
 *               │                       │        (next signer, after VALIDATING)
 *               ▼                       ▼                    ▼
 *          CANCELLED              CANCELLED              VALIDATING ──► COMPLETED
 *                                                             │
 *                                                             ▼
 *                                                             FAILED (then blocks)
 * </pre>
 */
public enum WorkflowState {

    /** Created, manifest not yet embedded. */
    DRAFT,

    /** Manifest embedded, revision 0 stored; not yet handed to any signer. */
    READY_FOR_SIGNING,

    /** A signer holds the current task and is reviewing/signing. */
    WAITING_FOR_SIGNER,

    /** A signed revision was uploaded; validation is running. */
    SIGNATURE_SUBMITTED,

    /** Server validation completed; outcome decided by the service. */
    VALIDATING,

    /** Validation failed; the workflow is blocked and needs intervention. */
    FAILED,

    /** Cancelled by an authorized user; terminal. */
    CANCELLED,

    /** All signers completed; terminal. */
    COMPLETED;

    /** Whether a transition from this state to {@code target} is legal. */
    public boolean canTransitionTo(WorkflowState target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case DRAFT -> target == READY_FOR_SIGNING || target == CANCELLED;
            case READY_FOR_SIGNING -> target == WAITING_FOR_SIGNER || target == CANCELLED;
            case WAITING_FOR_SIGNER -> target == SIGNATURE_SUBMITTED || target == CANCELLED;
            case SIGNATURE_SUBMITTED -> target == VALIDATING;
            case VALIDATING -> target == WAITING_FOR_SIGNER
                    || target == COMPLETED
                    || target == FAILED;
            case FAILED, CANCELLED, COMPLETED -> false;
        };
    }
}

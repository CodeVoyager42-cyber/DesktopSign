package com.example.signer.workflow;

/**
 * Lifecycle of one signer inside a signing workflow.
 *
 * <p>Transitions are validated by {@link SigningWorkflow#updateSignerStatus}
 * so that, for example, a signer cannot be marked {@code SIGNED} while the
 * workflow is still waiting for their token. Allowed transitions:</p>
 *
 * <pre>
 * PENDING --&gt; READY --&gt; AUTHENTICATING --&gt; SIGNING --&gt; SIGNED
 *    |          |              |              |
 *    |          |              |              +--&gt; FAILED
 *    |          |              +--&gt; CANCELLED
 *    |          +-&gt; CANCELLED
 *    +-&gt; CANCELLED
 *
 * CANCELLED and FAILED both allow a fresh start back to PENDING.
 * </pre>
 */
public enum SigningStatus {

    /** Waiting for their turn in the sequential signing order. */
    PENDING,

    /** Next in line: document prepared, waiting for token + PIN. */
    READY,

    /** PIN accepted by the token, login in progress. */
    AUTHENTICATING,

    /** Cryptographic signature currently being created. */
    SIGNING,

    /** Signature created and verified against this signer's revision. */
    SIGNED,

    /** Signing or verification failed for this signer. */
    FAILED,

    /** The signer's turn was cancelled before a signature was created. */
    CANCELLED;

    /** Whether this signer's work is complete and verified. */
    public boolean isDone() {
        return this == SIGNED;
    }

    /** Whether the workflow is currently busy with this signer. */
    public boolean isActive() {
        return this == AUTHENTICATING || this == SIGNING;
    }

    /** Whether this signer reached a terminal state (success or failure). */
    public boolean isTerminal() {
        return this == SIGNED || this == FAILED || this == CANCELLED;
    }
}

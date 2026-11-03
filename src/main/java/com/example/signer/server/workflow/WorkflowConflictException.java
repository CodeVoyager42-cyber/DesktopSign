package com.example.signer.server.workflow;

/**
 * The workflow changed underneath the caller: a store refused to persist an
 * aggregate because the stored copy is newer than the one the caller loaded
 * (optimistic concurrency), or because the save would have regressed a
 * terminal state or an accepted signature.
 *
 * <p>Mapped to HTTP 409 {@code WORKFLOW_CONFLICT}. Callers must reload the
 * workflow and decide again; the failed operation never partially applies.
 */
public class WorkflowConflictException extends RuntimeException {

    public WorkflowConflictException(String message) {
        super(message);
    }

    public WorkflowConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.example.signer.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Domain model of a local signing workflow: one signer, signing one
 * document on this computer.
 *
 * <pre>
 * SigningWorkflow
 *     |
 *     +-- Signer 1 --&gt; token --&gt; signature 1 (revision 1)
 * </pre>
 *
 * <p>The signer list is kept as a list rather than a single field because
 * the status machine, the workspace screen and the audit trail all read it
 * that way; it simply never holds more than one entry. Status transitions
 * are validated centrally in
 * {@link #updateSignerStatus(int, SigningStatus)}.</p>
 */
public class SigningWorkflow {

    private static final Logger log = LoggerFactory.getLogger(SigningWorkflow.class);

    private final List<Signer> signers;

    /** Creates a workflow whose single signer starts out PENDING. */
    public static SigningWorkflow create() {
        return new SigningWorkflow();
    }

    public SigningWorkflow() {
        this.signers = List.of(new Signer(1));
    }

    /** Immutable, single-element list of this workflow's signers. */
    public List<Signer> getSigners() {
        return signers;
    }

    /** Always 1: one document, one signer. */
    public int signerCount() {
        return signers.size();
    }

    /** @throws IndexOutOfBoundsException for an invalid 1-based order */
    public Signer getSigner(int order) {
        return signers.get(order - 1);
    }

    /** The one signer, while they still have something to do. */
    public Signer getCurrentSigner() {
        Signer signer = signers.get(0);
        return signer.getStatus().isDone() ? null : signer;
    }

    /** Whether the signature is in place and verified. */
    public boolean isComplete() {
        return signers.get(0).getStatus() == SigningStatus.SIGNED;
    }

    /** Whether signing failed or was cancelled. */
    public boolean hasFailed() {
        SigningStatus status = signers.get(0).getStatus();
        return status == SigningStatus.FAILED || status == SigningStatus.CANCELLED;
    }

    /** Marks the signer as next in line. */
    public void start() {
        log.info("Starting signing workflow");
        signers.get(0).setStatus(SigningStatus.READY);
    }

    /**
     * Validates and applies a status change.
     *
     * <p>Rules: a status may only move forward (repeatedly setting the same
     * value is a harmless no-op). {@code FAILED} and {@code CANCELLED} are
     * allowed from any active state and may later be reset to
     * {@code PENDING} via {@link #resetSigner(int)} to retry.</p>
     *
     * @return the updated {@link Signer}
     */
    public Signer updateSignerStatus(int order, SigningStatus newStatus) {
        Signer signer = getSigner(order);
        SigningStatus current = signer.getStatus();
        if (current == newStatus) {
            return signer;
        }
        boolean legalForward =
                (current == SigningStatus.PENDING && newStatus == SigningStatus.READY)
                        || (current == SigningStatus.READY
                            && newStatus == SigningStatus.AUTHENTICATING)
                        || (current == SigningStatus.AUTHENTICATING
                            && newStatus == SigningStatus.SIGNING)
                        || (current == SigningStatus.SIGNING
                            && newStatus == SigningStatus.SIGNED)
                        || (newStatus == SigningStatus.FAILED
                            || newStatus == SigningStatus.CANCELLED);
        if (!legalForward) {
            throw new IllegalStateException("Illegal signer status transition: "
                    + order + " " + current + " -> " + newStatus);
        }
        log.info("Signer {} status: {} -> {}", order, current, newStatus);
        signer.setStatus(newStatus);
        return signer;
    }

    /** Returns a FAILED/CANCELLED signer to READY so signing can be retried. */
    public void resetSigner(int order) {
        Signer signer = getSigner(order);
        if (signer.getStatus() != SigningStatus.FAILED
                && signer.getStatus() != SigningStatus.CANCELLED) {
            throw new IllegalStateException("Only FAILED or CANCELLED signers can "
                    + "be reset, but signer " + order + " is " + signer.getStatus());
        }
        signer.setStatus(SigningStatus.READY);
        log.info("Signer {} reset to {}", order, signer.getStatus());
    }

    @Override
    public String toString() {
        return "SigningWorkflow[" + (isComplete() ? "complete" : "in progress") + "]";
    }
}

package com.example.signer.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Domain model of a multi-signing workflow: a mode plus the ordered list of
 * signers it requires.
 *
 * <pre>
 * SigningWorkflow (mode, signers)
 *     |
 *     +-- Signer 1 --&gt; token A --&gt; signature 1 (revision 1)
 *     +-- Signer 2 --&gt; token B --&gt; signature 2 (revision 2)
 *     +-- Signer 3 --&gt; token C --&gt; signature 3 (revision 3)
 * </pre>
 *
 * <p>The workflow enforces <em>sequential</em> signing today (signer n only
 * becomes {@code READY} once signer n-1 is {@code SIGNED}) without hard-coding
 * that policy: a future parallel engine can iterate the same signer list in a
 * different order. Status transitions are validated centrally in
 * {@link #updateSignerStatus(int, SigningStatus)}.</p>
 */
public class SigningWorkflow {

    private static final Logger log = LoggerFactory.getLogger(SigningWorkflow.class);

    private final SigningMode mode;
    private final List<Signer> signers;

    /** Creates a workflow for {@code mode} with signers in PENDING state. */
    public static SigningWorkflow of(SigningMode mode) {
        return new SigningWorkflow(mode);
    }

    public SigningWorkflow(SigningMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        List<Signer> created = new ArrayList<>(mode.signerCount());
        for (int i = 1; i <= mode.signerCount(); i++) {
            created.add(new Signer(i));
        }
        this.signers = Collections.unmodifiableList(created);
    }

    public SigningMode getMode() {
        return mode;
    }

    /** Immutable, ordered list of this workflow's signers. */
    public List<Signer> getSigners() {
        return signers;
    }

    /** Number of signers the mode requires. */
    public int signerCount() {
        return mode.signerCount();
    }

    /** @throws IndexOutOfBoundsException for an invalid 1-based order */
    public Signer getSigner(int order) {
        return signers.get(order - 1);
    }

    /** The signer whose turn it currently is (first non-done signer). */
    public Signer getCurrentSigner() {
        return signers.stream()
                .filter(s -> !s.getStatus().isDone())
                .findFirst()
                .orElse(null);
    }

    /** Whether every signer has a verified signature. */
    public boolean isComplete() {
        return signers.stream().allMatch(s -> s.getStatus() == SigningStatus.SIGNED);
    }

    /** Whether any signer failed or the workflow was cancelled. */
    public boolean hasFailed() {
        return signers.stream()
                .anyMatch(s -> s.getStatus() == SigningStatus.FAILED
                        || s.getStatus() == SigningStatus.CANCELLED);
    }

    /** Marks signer 1 as next in line; the rest stay PENDING. */
    public void start() {
        if (signers.isEmpty()) {
            throw new IllegalStateException("Workflow has no signers");
        }
        log.info("Starting {} signing workflow with {} signer(s)", mode, signers.size());
        signers.get(0).setStatus(SigningStatus.READY);
    }

    /**
     * Validates and applies a status change.
     *
     * <p>Rules: a status may only move forward (repeatedly setting the same
     * value is a harmless no-op), and only the signer currently marked
     * {@code READY} may advance — this is the sequential-ordering guarantee.
     * {@code FAILED} and {@code CANCELLED} are allowed from any active state
     * and may later be reset to {@code PENDING} via {@link #resetSigner(int)}
     * to retry.</p>
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
        if (newStatus == SigningStatus.READY
                && order > 1
                && !signers.get(order - 2).getStatus().isDone()) {
            // Sequential-ordering guarantee: nobody is next while an earlier
            // signer is still pending, signing or failed.
            throw new IllegalStateException("Signer " + order
                    + " cannot become READY before earlier signers finished");
        }
        log.info("Signer {} status: {} -> {}", order, current, newStatus);
        signer.setStatus(newStatus);
        if (newStatus == SigningStatus.SIGNED && order < signers.size()) {
            signers.get(order).setStatus(SigningStatus.READY); // hand over the baton
        }
        return signer;
    }

    /** Returns a FAILED/CANCELLED signer to PENDING so the turn can be retried. */
    public void resetSigner(int order) {
        Signer signer = getSigner(order);
        if (signer.getStatus() != SigningStatus.FAILED
                && signer.getStatus() != SigningStatus.CANCELLED) {
            throw new IllegalStateException("Only FAILED or CANCELLED signers can "
                    + "be reset, but signer " + order + " is " + signer.getStatus());
        }
        signer.setStatus(SigningStatus.PENDING);
        if (order == 1 || signers.get(order - 2).getStatus().isDone()) {
            signer.setStatus(SigningStatus.READY);
        }
        log.info("Signer {} reset to {}", order, signer.getStatus());
    }

    @Override
    public String toString() {
        return "SigningWorkflow[" + mode + ", " + signers.size() + " signer(s), "
                + (isComplete() ? "complete" : "in progress") + "]";
    }
}

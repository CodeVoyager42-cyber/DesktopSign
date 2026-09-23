package com.example.signer.workflow;

import java.util.List;

/**
 * The effective state of a portable workflow after reconciling the manifest
 * against the PDF's actual signature chain (see
 * {@link WorkflowReconciliationService}).
 *
 * <p>This — not the manifest, not the UI — is what the application acts on.
 * {@code status} classifies the document; {@code steps} carries the per-signer
 * view; {@code currentStepOrder} is the 1-based order of the signer whose turn
 * it is ({@code null} when none applies); {@code signingBlockers} lists every
 * independent reason why signing is currently forbidden (empty = allowed).
 */
public record EffectiveWorkflowState(
        WorkflowStatus status,
        WorkflowManifest manifest,
        List<StepView> steps,
        Integer currentStepOrder,
        List<String> warnings,
        List<String> signingBlockers) {

    /** Overall classification of the document's workflow. */
    public enum WorkflowStatus {
        /** No signer has produced a valid signature yet. */
        NOT_STARTED,
        /** At least one valid signature, at least one step pending. */
        IN_PROGRESS,
        /** Every step fulfilled by a valid signature, nothing extra. */
        COMPLETED,
        /** The signature chain is broken (document tampered/corrupted). */
        SIGNATURE_INVALID,
        /** Manifest claims disagree with the signature chain. */
        MANIFEST_INCONSISTENT
    }

    /** Per-signer view for timelines and workspace UIs. */
    public record StepView(
            int order,
            String displayName,
            CertificateIdentity certificate,
            boolean fulfilled,
            boolean verified,
            java.time.Instant signedAt,
            boolean yourTurn) {
    }

    public boolean canSign() {
        return signingBlockers.isEmpty();
    }

    /** The step whose turn it is, or {@code null}. */
    public WorkflowSignerStep currentStep() {
        if (currentStepOrder == null || manifest == null) {
            return null;
        }
        return manifest.stepsInOrder().stream()
                .filter(s -> s.order() == currentStepOrder)
                .findFirst()
                .orElse(null);
    }
}

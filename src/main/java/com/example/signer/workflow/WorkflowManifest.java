package com.example.signer.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Portable, JSON-serialised workflow manifest that travels inside the PDF.
 *
 * <p>The manifest is <b>workflow metadata</b>, not cryptographic truth — what
 * has actually been signed is determined solely by the PDF signature chain.
 * Reconciliation treats it accordingly.
 *
 * <p>Schema-versioned: {@value #CURRENT} is the version this build writes and
 * fully understands. A manifest carrying a higher version was written by a
 * newer build: load it for inspection/verification but refuse to sign
 * (signing would rewrite the manifest with lossy knowledge).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowManifest(
        @JsonProperty("workflowManifestVersion") int version,
        @JsonProperty("workflowId") String workflowId,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("updatedAt") Instant updatedAt,
        @JsonProperty("signers") List<WorkflowSignerStep> signers) {

    /** The only schema version this build writes. */
    public static final int CURRENT = 1;

    /** Lowest version this build can read. */
    public static final int MIN_SUPPORTED = 1;

    public WorkflowManifest {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        signers = signers == null ? List.of() : List.copyOf(signers);
        if (workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        if (signers.isEmpty()) {
            throw new IllegalArgumentException("workflow needs at least one signer");
        }
        for (int i = 0; i < signers.size(); i++) {
            if (signers.get(i).order() != i + 1) {
                throw new IllegalArgumentException(
                        "signer order must be 1.." + signers.size() + " without gaps");
            }
        }
    }

    /**
     * Whether this build may sign under this manifest's schema.
     * Higher versions are readable but not signable.
     */
    public boolean isSupportedForSigning() {
        return version >= MIN_SUPPORTED && version <= CURRENT;
    }

    /** Whether this build can read the manifest at all. */
    public boolean isReadable() {
        return version >= MIN_SUPPORTED;
    }

    /** The step that must sign next, or {@code null} when all steps are done. */
    public WorkflowSignerStep nextPendingStep() {
        for (WorkflowSignerStep step : signers) {
            if (step.revisionIndex() == null) {
                return step;
            }
        }
        return null;
    }

    /** Steps in order (defensive copy of the list, records shared). */
    public List<WorkflowSignerStep> stepsInOrder() {
        return signers;
    }
}

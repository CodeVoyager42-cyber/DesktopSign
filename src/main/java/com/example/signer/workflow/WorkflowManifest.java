package com.example.signer.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

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
        @JsonProperty("originalDocumentSha256")
        @JsonInclude(JsonInclude.Include.NON_NULL) String originalDocumentSha256,
        @JsonProperty("signingPolicy")
        @JsonInclude(JsonInclude.Include.NON_NULL) WorkflowSigningPolicy signingPolicy,
        @JsonProperty("signers") List<WorkflowSignerStep> signers) {

    /** The only schema version this build writes. */
    public static final int CURRENT = 1;

    /** Lowest version this build can read. */
    public static final int MIN_SUPPORTED = 1;

    /** Lower-case hex SHA-256. */
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    /**
     * Backward-compatible constructor for callers created before document
     * binding existed: the workflow is simply not bound to a specific
     * original document and uses the default (sequential) policy.
     */
    public WorkflowManifest(int version, String workflowId, Instant createdAt,
                            Instant updatedAt, List<WorkflowSignerStep> signers) {
        this(version, workflowId, createdAt, updatedAt, null,
                WorkflowSigningPolicy.SEQUENTIAL, signers);
    }

    /**
     * Backward-compatible constructor for callers created before signing
     * policies existed: not bound to an original document, explicit policy.
     */
    public WorkflowManifest(int version, String workflowId, Instant createdAt,
                            Instant updatedAt, WorkflowSigningPolicy signingPolicy,
                            List<WorkflowSignerStep> signers) {
        this(version, workflowId, createdAt, updatedAt, null, signingPolicy, signers);
    }

    public WorkflowManifest {
        signingPolicy = signingPolicy == null
                ? WorkflowSigningPolicy.SEQUENTIAL : signingPolicy;
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (originalDocumentSha256 != null
                && !SHA256_HEX.matcher(originalDocumentSha256).matches()) {
            throw new IllegalArgumentException(
                    "originalDocumentSha256 must be lower-case hex SHA-256");
        }
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

    /** The policy governing who may hold the next signing turn. */
    public WorkflowSigningPolicy policy() {
        return signingPolicy;
    }

    /** A copy of this manifest bound to {@code sha256Hex} (lower-case hex). */
    public WorkflowManifest withOriginalDocumentSha256(String sha256Hex) {
        return new WorkflowManifest(version, workflowId, createdAt, updatedAt,
                sha256Hex, signingPolicy, signers);
    }
}

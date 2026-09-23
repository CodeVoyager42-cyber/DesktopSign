package com.example.signer.workflow;

import com.example.signer.signing.SignaturePlacement;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * One ordered step of a portable signing workflow.
 *
 * <p>{@code placement} is optional ({@code null} = sign without a visible
 * signature box). {@code signedAt} records when this step's signature was
 * created (local clock, informational only — it is never presented as a
 * trusted timestamp). {@code revisionIndex} is filled by reconciliation with
 * the index of the signature (0-based, oldest first) that fulfilled this
 * step, or {@code null} while the step is unfulfilled.
 *
 * <p>JSON-friendly via Jackson with {@code ignoreUnknown} for forward
 * compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowSignerStep(
        @JsonProperty("order") int order,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("certificate") CertificateIdentity certificate,
        @JsonProperty("placement") SignaturePlacement placement,
        @JsonProperty("signedAt") Instant signedAt,
        @JsonProperty("revisionIndex") Integer revisionIndex) {

    public WorkflowSignerStep {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(certificate, "certificate");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }

    /** Convenience factory for workflow creation (nothing signed yet). */
    public static WorkflowSignerStep of(int order,
                                        String displayName,
                                        CertificateIdentity certificate,
                                        SignaturePlacement placement) {
        return new WorkflowSignerStep(order, displayName, certificate, placement, null, null);
    }

    public WorkflowSignerStep withSignedAt(Instant signedAt) {
        return new WorkflowSignerStep(order, displayName, certificate, placement,
                signedAt, revisionIndex);
    }

    public WorkflowSignerStep withRevisionIndex(Integer revisionIndex) {
        return new WorkflowSignerStep(order, displayName, certificate, placement,
                signedAt, revisionIndex);
    }

    /** The step's cryptographic identity. */
    public CertificateIdentity identity() {
        return certificate;
    }
}

package com.example.signer.server.workflow;

import com.example.signer.signing.SignaturePlacement;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One signer slot of a server-coordinated workflow.
 *
 * <p>Identity is the SHA-256 fingerprint of the expected signing certificate
 * (colon-separated hex), bound to the slot by {@code bindCertificate} —
 * either at workflow creation (captured from the signer's token by the
 * creator) or later, on the signer's own machine (the recommended path: the
 * creator never needs physical access to another user's USB token).
 *
 * <p>Signer identity (server user) and certificate identity (fingerprint) are
 * separate concepts: {@code userId} authorizes the turn and the API call;
 * {@code certificateSha256} authorizes the cryptographic signature.
 *
 * @param order              1-based signing order (immutable)
 * @param userId             the server user who must sign this slot
 * @param certificateSha256  expected signer certificate fingerprint, or
 *                           {@code null} while UNBOUND (no revision 0 can be
 *                           prepared until every slot is bound)
 * @param boundAt            when the certificate was bound (server clock)
 * @param signed             whether this slot's signature has been accepted
 * @param signedAt           when the signature was accepted (server clock)
 * @param signingOperationId idempotency key of the accepted submission
 * @param placement          where the accepted visible signature appeared
 *                           (PDF points, origin bottom-left), extracted by
 *                           the server from the accepted revision;
 *                           {@code null} for unsigned slots and invisible
 *                           signatures
 */
public record WorkflowSignerRecord(
        @JsonProperty("order") int order,
        @JsonProperty("userId") String userId,
        @JsonProperty("certificateSha256") String certificateSha256,
        @JsonProperty("boundAt") Instant boundAt,
        @JsonProperty("signed") boolean signed,
        @JsonProperty("signedAt") Instant signedAt,
        @JsonProperty("signingOperationId") String signingOperationId,
        @JsonProperty("placement") SignaturePlacement placement) {

    public WorkflowSignerRecord {
        if (order < 1) {
            throw new IllegalArgumentException("order must be >= 1");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (certificateSha256 != null && certificateSha256.isBlank()) {
            throw new IllegalArgumentException(
                    "certificateSha256 must not be blank (use null for UNBOUND)");
        }
    }

    @JsonCreator
    public static WorkflowSignerRecord of(
            @JsonProperty("order") int order,
            @JsonProperty("userId") String userId,
            @JsonProperty("certificateSha256") String certificateSha256,
            @JsonProperty("boundAt") Instant boundAt,
            @JsonProperty("signed") boolean signed,
            @JsonProperty("signedAt") Instant signedAt,
            @JsonProperty("signingOperationId") String signingOperationId) {
        return new WorkflowSignerRecord(order, userId, certificateSha256,
                boundAt, signed, signedAt, signingOperationId, null);
    }

    /** Legacy-shape factory retained for old persisted snapshots. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public static WorkflowSignerRecord of(int order, String userId,
            String certificateSha256, Instant boundAt, boolean signed,
            Instant signedAt, String signingOperationId,
            @SuppressWarnings("unused") boolean legacyMarker) {
        return new WorkflowSignerRecord(order, userId, certificateSha256,
                boundAt, signed, signedAt, signingOperationId, null);
    }

    /** A fresh, UNBOUND slot for {@code userId} at signing position {@code order}. */
    public static WorkflowSignerRecord unbound(int order, String userId) {
        return new WorkflowSignerRecord(order, userId, null, null, false, null, null, null);
    }

    /** A slot with the certificate bound at creation time. */
    public static WorkflowSignerRecord bound(int order, String userId,
                                             String certificateSha256) {
        return new WorkflowSignerRecord(order, userId, certificateSha256,
                Instant.now(), false, null, null, null);
    }

    /** Whether a certificate is bound to this slot. */
    public boolean isBound() {
        return certificateSha256 != null;
    }

    /** A copy with {@code fingerprint} bound now. */
    public WorkflowSignerRecord asBound(String fingerprint) {
        return new WorkflowSignerRecord(order, userId, fingerprint,
                Instant.now(), signed, signedAt, signingOperationId, placement);
    }

    /** A copy marked signed at {@code at} under idempotency key {@code opId}. */
    public WorkflowSignerRecord asSigned(Instant at, String opId) {
        return new WorkflowSignerRecord(order, userId, certificateSha256,
                boundAt, true, at, opId, placement);
    }

    /** A copy marked signed that also records the accepted visible placement. */
    public WorkflowSignerRecord asSigned(Instant at, String opId,
                                         SignaturePlacement acceptedPlacement) {
        return new WorkflowSignerRecord(order, userId, certificateSha256,
                boundAt, true, at, opId, acceptedPlacement);
    }
}

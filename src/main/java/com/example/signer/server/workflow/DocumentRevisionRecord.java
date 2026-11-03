package com.example.signer.server.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One immutable document revision in a server-coordinated workflow.
 *
 * <p>Revision 0 is the prepared document (workflow manifest embedded, no
 * signatures); each accepted submission adds revision N+1 whose parent is N.
 *
 * @param revisionNumber  0-based, contiguous, never reused
 * @param parentRevision  revision this one was produced from ({@code -1} for revision 0)
 * @param sha256          lower-case hex SHA-256 of the stored file bytes
 * @param createdBy       authenticated user who produced this revision ({@code "system"} for revision 0)
 */
public record DocumentRevisionRecord(
        @JsonProperty("revisionNumber") int revisionNumber,
        @JsonProperty("parentRevision") int parentRevision,
        @JsonProperty("sha256") String sha256,
        @JsonProperty("sizeBytes") long sizeBytes,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("createdBy") String createdBy) {

    public DocumentRevisionRecord {
        if (revisionNumber < 0) {
            throw new IllegalArgumentException("revisionNumber must be >= 0");
        }
        if (revisionNumber == 0 ? parentRevision != -1 : parentRevision != revisionNumber - 1) {
            throw new IllegalArgumentException(
                    "parentRevision must be revisionNumber-1 (or -1 for revision 0)");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be lower-case hex SHA-256");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy must not be blank");
        }
    }

    @JsonCreator
    public static DocumentRevisionRecord of(
            @JsonProperty("revisionNumber") int revisionNumber,
            @JsonProperty("parentRevision") int parentRevision,
            @JsonProperty("sha256") String sha256,
            @JsonProperty("sizeBytes") long sizeBytes,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("createdBy") String createdBy) {
        return new DocumentRevisionRecord(revisionNumber, parentRevision, sha256,
                sizeBytes, createdAt, createdBy);
    }
}

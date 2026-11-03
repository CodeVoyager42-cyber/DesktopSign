package com.example.signer.server.workflow;

import java.time.Instant;

/**
 * One audit event of a server-coordinated workflow. Immutable, append-only.
 *
 * <p>Never carries PINs, private keys, or token secrets — by construction
 * (only workflow-relevant facts are recorded).
 *
 * @param eventType one of {@link AuditEventType}
 * @param actor     authenticated user who caused the event ({@code "system"} for server-internal)
 */
public record AuditEventRecord(
        long sequence,
        AuditEventType eventType,
        String actor,
        String detail,
        Instant occurredAt) {
}

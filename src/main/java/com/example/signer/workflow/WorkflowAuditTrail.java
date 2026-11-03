package com.example.signer.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory audit trail of one signing workflow (§41). Events are also
 * written to the application log. The trail contains workflow facts only —
 * never PINs, never key material (the API accepts event text, and no caller
 * ever passes secrets into it).
 */
public final class WorkflowAuditTrail {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAuditTrail.class);

    /** One timestamped workflow event. */
    public record AuditEvent(Instant at, String event) {
    }

    private final List<AuditEvent> events = new ArrayList<>();

    /** Records a workflow event with the current time. */
    public void record(String event) {
        AuditEvent auditEvent = new AuditEvent(Instant.now(), event);
        events.add(auditEvent);
        log.info("[audit] {}", event);
    }

    /** All recorded events, in order. */
    public List<AuditEvent> events() {
        return List.copyOf(events);
    }

    /** Removes all events (used when a workflow is restarted). */
    public void clear() {
        events.clear();
    }
}

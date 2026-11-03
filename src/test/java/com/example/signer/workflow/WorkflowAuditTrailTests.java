package com.example.signer.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit trail behaviour (§41): ordered events, no secrets, clear(). */
class WorkflowAuditTrailTests {

    @Test
    void recordsEventsInOrderWithTimestamps() {
        WorkflowAuditTrail audit = new WorkflowAuditTrail();
        audit.record("Workflow created");
        audit.record("Signer 1 turn started");
        audit.record("Signer 1 signed");

        List<WorkflowAuditTrail.AuditEvent> events = audit.events();
        assertThat(events).hasSize(3);
        assertThat(events.get(0).event()).isEqualTo("Workflow created");
        assertThat(events.get(1).event()).isEqualTo("Signer 1 turn started");
        assertThat(events.get(2).event()).isEqualTo("Signer 1 signed");
        assertThat(events.get(0).at()).isNotNull();
        assertThat(events.get(1).at()).isAfterOrEqualTo(events.get(0).at());
    }

    @Test
    void clearRemovesAllEvents() {
        WorkflowAuditTrail audit = new WorkflowAuditTrail();
        audit.record("one");
        audit.clear();
        assertThat(audit.events()).isEmpty();
    }
}

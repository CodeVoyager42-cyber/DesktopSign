package com.example.signer.server;

import com.example.signer.workflow.WorkflowSigningPolicy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Legacy creator recovery. Snapshots written before the creator was recorded
 * at workflow level carry no {@code createdBy}; importing them into
 * PostgreSQL failed with a NOT NULL violation on {@code workflows.created_by}.
 * The identity is nonetheless in the snapshot — revision 0 was stored by the
 * creator, and {@code WORKFLOW_CREATED} names the same actor — so it is
 * recovered rather than invented.
 */
class WorkflowFileStoreCreatorTests {

    @Test
    void explicitWorkflowLevelCreatorWins() {
        SnapshotDto dto = snapshot("carol", List.of(revision(0, "alice")),
                List.of(created(0, "alice")));

        assertThat(WorkflowFileStore.creatorOf(dto)).isEqualTo("carol");
    }

    @Test
    void legacySnapshotWithoutCreatorFallsBackToRevisionZeroAuthor() {
        SnapshotDto dto = snapshot(null, List.of(revision(0, "alice"), revision(1, "bob")),
                List.of(created(0, "alice")));

        assertThat(WorkflowFileStore.creatorOf(dto)).isEqualTo("alice");
    }

    @Test
    void auditActorIsUsedWhenNoRevisionNamesACreator() {
        SnapshotDto dto = snapshot(null, List.of(), List.of(created(0, "dave")));

        assertThat(WorkflowFileStore.creatorOf(dto)).isEqualTo("dave");
    }

    @Test
    void snapshotWithoutAnyCreatorSignalReturnsNull() {
        SnapshotDto dto = snapshot(null, List.of(), List.of());

        assertThat(WorkflowFileStore.creatorOf(dto)).isNull();
    }

    /* ------------------------------------------------------------------ */

    private static SnapshotDto snapshot(String createdBy,
                                        List<DocumentRevisionRecord> revisions,
                                        List<AuditEventRecord> audit) {
        return new SnapshotDto("contract.pdf", WorkflowSigningPolicy.SEQUENTIAL,
                WorkflowState.WAITING_FOR_SIGNER, null, null, null, createdBy,
                List.of(new WorkflowSignerRecord(1, "alice", null, null, false,
                        null, null, null)),
                revisions, audit);
    }

    private static DocumentRevisionRecord revision(int number, String createdBy) {
        return new DocumentRevisionRecord(number, number == 0 ? -1 : number - 1,
                "0".repeat(63) + "1", 1024, Instant.parse("2026-10-01T10:00:00Z"),
                createdBy);
    }

    private static AuditEventRecord created(long sequence, String actor) {
        return new AuditEventRecord(sequence, AuditEventType.WORKFLOW_CREATED, actor,
                "Workflow created", Instant.parse("2026-10-01T10:00:00Z"));
    }
}

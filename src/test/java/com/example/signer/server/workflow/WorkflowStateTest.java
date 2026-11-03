package com.example.signer.server.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * State machine + aggregate invariant tests at the unit level.
 *
 * <p>A document has exactly one signer, so there is no signing order, no
 * turn to claim and no next signer: the invariants here are about that one
 * slot, and about refusing anything that pretends there are more.
 */
class WorkflowStateTest {

    private static final String FP_A =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
            + ":AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    private static WorkflowSignerRecord bound(int order, String userId) {
        return WorkflowSignerRecord.bound(order, userId, FP_A);
    }

    private static SigningWorkflowAggregate workflow(String id,
                                                     WorkflowSignerRecord signer) {
        return SigningWorkflowAggregate.create(id, "doc.pdf", signer, signer.userId());
    }

    /* ── the state machine ────────────────────────────────────────────── */

    @Test
    void happyPathTransitionsAreLegal() {
        assertThat(WorkflowState.DRAFT.canTransitionTo(WorkflowState.READY_FOR_SIGNING))
                .isTrue();
        assertThat(WorkflowState.READY_FOR_SIGNING.canTransitionTo(WorkflowState.WAITING_FOR_SIGNER))
                .isTrue();
        assertThat(WorkflowState.WAITING_FOR_SIGNER.canTransitionTo(WorkflowState.SIGNATURE_SUBMITTED))
                .isTrue();
        assertThat(WorkflowState.SIGNATURE_SUBMITTED.canTransitionTo(WorkflowState.VALIDATING))
                .isTrue();
        assertThat(WorkflowState.VALIDATING.canTransitionTo(WorkflowState.COMPLETED))
                .isTrue();
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertThat(WorkflowState.DRAFT.canTransitionTo(WorkflowState.COMPLETED)).isFalse();
        assertThat(WorkflowState.COMPLETED.canTransitionTo(WorkflowState.WAITING_FOR_SIGNER))
                .isFalse();
        assertThat(WorkflowState.CANCELLED.canTransitionTo(WorkflowState.WAITING_FOR_SIGNER))
                .isFalse();
        assertThat(WorkflowState.READY_FOR_SIGNING.canTransitionTo(WorkflowState.COMPLETED))
                .isFalse();
        SigningWorkflowAggregate wf = workflow("WF-T1", bound(1, "alice"));
        assertThatThrownBy(() -> wf.transition(WorkflowState.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    /* ── one signer, slot 1 ───────────────────────────────────────────── */

    /**
     * A stored workflow with more than one slot predates single-signer
     * signing. It must fail loudly rather than have a signature silently
     * dropped.
     */
    @Test
    void aggregateRefusesMoreThanOneSigner() {
        List<WorkflowSignerRecord> two = List.of(bound(1, "alice"), bound(2, "bob"));
        List<DocumentRevisionRecord> zero = List.of(new DocumentRevisionRecord(
                0, -1, "0".repeat(64), 0, Instant.now(), "system"));
        assertThatThrownBy(() -> SigningWorkflowAggregate.rehydrate(
                "WF-1", "doc.pdf", two, zero, List.of(),
                WorkflowState.DRAFT, null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one signer");
    }

    @Test
    void aggregateRefusesASlotThatIsNotOrderOne() {
        assertThatThrownBy(() -> workflow("WF-2", bound(2, "alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order 1");
    }

    @Test
    void theSignerHoldsTheTurnUntilTheSignatureIsAccepted() {
        SigningWorkflowAggregate wf = workflow("WF-3", bound(1, "alice"));
        assertThat(wf.currentSigner().userId()).isEqualTo("alice");
        assertThat(wf.maySubmit("alice")).isTrue();
        assertThat(wf.maySubmit("bob")).isFalse();
        assertThat(wf.allSigned()).isFalse();

        wf.markSignedByUser("alice", "op-1");

        assertThat(wf.allSigned()).isTrue();
        assertThat(wf.currentSigner()).as("nobody signs after the one signer").isNull();
        assertThat(wf.maySubmit("alice")).isFalse();
        assertThat(wf.signer().signed()).isTrue();
        assertThat(wf.signer().signingOperationId()).isEqualTo("op-1");
    }

    @Test
    void onlyThePendingSlotsOwnerCanSign() {
        SigningWorkflowAggregate wf = workflow("WF-4", bound(1, "alice"));
        assertThatThrownBy(() -> wf.markSignedByUser("mallory", "op-x"))
                .isInstanceOf(IllegalStateException.class);
        wf.markSignedByUser("alice", "op-1");
        assertThatThrownBy(() -> wf.markSignedByUser("alice", "op-2"))
                .as("a slot is signed once").isInstanceOf(IllegalStateException.class);
    }

    /* ── revisions ────────────────────────────────────────────────────── */

    @Test
    void revisionNumbersMustBeContiguous() {
        SigningWorkflowAggregate wf = workflow("WF-5", bound(1, "alice"));
        assertThatThrownBy(() -> wf.appendRevision(new DocumentRevisionRecord(
                5, 4, "0".repeat(64), 1, Instant.now(), "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* ── certificate binding ──────────────────────────────────────────── */

    @Test
    void anUnboundSlotBlocksRevision0Preparation() {
        SigningWorkflowAggregate wf = workflow("WF-B1",
                WorkflowSignerRecord.unbound(1, "alice"));
        assertThat(wf.allBound()).isFalse();
        assertThat(wf.status()).isEqualTo(WorkflowState.DRAFT);

        wf.bindCertificate("alice", "AB".repeat(32));

        assertThat(wf.allBound()).isTrue();
        assertThat(wf.signer().isBound()).isTrue();
        assertThat(wf.signer().boundAt()).isNotNull();
    }

    @Test
    void bindRejectsUnknownUserAndASecondCertificate() {
        SigningWorkflowAggregate wf = workflow("WF-B2",
                WorkflowSignerRecord.unbound(1, "alice"));
        assertThatThrownBy(() -> wf.bindCertificate("mallory", "AB".repeat(32)))
                .isInstanceOf(IllegalStateException.class);
        wf.bindCertificate("alice", "AB".repeat(32));
        assertThatThrownBy(() -> wf.bindCertificate("alice", "CD".repeat(32)))
                .as("a slot's certificate is set once")
                .isInstanceOf(IllegalStateException.class);
    }

    /* ── snapshot round trip ──────────────────────────────────────────── */

    @Test
    void theSignedSlotSurvivesRehydration() {
        SigningWorkflowAggregate wf = workflow("WF-S1", bound(1, "alice"));
        wf.transition(WorkflowState.READY_FOR_SIGNING);
        wf.transition(WorkflowState.WAITING_FOR_SIGNER);
        wf.markSignedByUser("alice", "op-1");

        SigningWorkflowAggregate.Snapshot snap = wf.snapshot();
        SigningWorkflowAggregate restored = SigningWorkflowAggregate.rehydrate(
                snap.workflowId(), snap.documentName(), snap.signers(),
                snap.revisions(), snap.audit(), snap.status(),
                snap.blockedReason(), snap.createdBy());

        assertThat(restored.allSigned()).isTrue();
        assertThat(restored.currentSigner()).isNull();
        assertThat(restored.signer().signingOperationId()).isEqualTo("op-1");
        assertThat(restored.createdBy()).isEqualTo("alice");
    }
}

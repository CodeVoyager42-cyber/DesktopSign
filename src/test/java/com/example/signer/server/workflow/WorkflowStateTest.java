package com.example.signer.server.workflow;

import com.example.signer.workflow.WorkflowSigningPolicy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** State machine + aggregate invariant tests (§38 unit level). */
class WorkflowStateTest {

    private static final String FP_A =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
            + ":AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    private static WorkflowSignerRecord bound(int order, String userId) {
        return WorkflowSignerRecord.bound(order, userId, FP_A);
    }

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
        assertThat(WorkflowState.VALIDATING.canTransitionTo(WorkflowState.WAITING_FOR_SIGNER))
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
    }

    @Test
    void aggregateRejectsNonContiguousSignerOrder() {
        List<WorkflowSignerRecord> bad = List.of(
                bound(1, "alice"),
                bound(3, "bob"));
        assertThatThrownBy(() -> SigningWorkflowAggregate.create(
                "WF-1", "doc.pdf", WorkflowSigningPolicy.SEQUENTIAL, bad, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markSignedAdvancesToNextSigner() {
        SigningWorkflowAggregate wf = SigningWorkflowAggregate.create(
                "WF-2", "doc.pdf", WorkflowSigningPolicy.SEQUENTIAL,
                List.of(bound(1, "alice"), bound(2, "bob")), "alice");
        assertThat(wf.currentSigner().userId()).isEqualTo("alice");
        WorkflowSignerRecord next = wf.markSignedByUser("alice", "op-1");
        assertThat(next.userId()).isEqualTo("bob");
        assertThat(wf.signers().get(0).signed()).isTrue();
        assertThat(wf.signers().get(0).signingOperationId()).isEqualTo("op-1");
    }

    @Test
    void revisionNumbersMustBeContiguous() {
        SigningWorkflowAggregate wf = SigningWorkflowAggregate.create(
                "WF-3", "doc.pdf", WorkflowSigningPolicy.SEQUENTIAL,
                List.of(bound(1, "alice")), "alice");
        assertThatThrownBy(() -> wf.appendRevision(new DocumentRevisionRecord(
                5, 4, "0".repeat(64), 1, Instant.now(), "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* ── flexible policy: atomic turn claim (§11, §33) ─────────────────── */

    @Test
    void flexibleWithoutClaimHasNoCurrentSigner() {
        SigningWorkflowAggregate wf = SigningWorkflowAggregate.create(
                "WF-F1", "doc.pdf", WorkflowSigningPolicy.FLEXIBLE,
                List.of(bound(1, "alice"), bound(2, "bob")), "alice");
        wf.transition(WorkflowState.READY_FOR_SIGNING);
        wf.transition(WorkflowState.WAITING_FOR_SIGNER);
        assertThat(wf.currentSigner()).isNull();
        assertThat(wf.pendingSigners()).extracting(WorkflowSignerRecord::userId)
                .containsExactly("alice", "bob");
        // Still eligible to claim, but not (yet) allowed to submit.
        assertThat(wf.isEligibleSigner("bob")).isTrue();
        assertThat(wf.maySubmit("bob")).isFalse();
    }

    @Test
    void flexibleClaimGivesExclusiveTurnAndIdempotentReclaim() {
        SigningWorkflowAggregate wf = SigningWorkflowAggregate.create(
                "WF-F2", "doc.pdf", WorkflowSigningPolicy.FLEXIBLE,
                List.of(bound(1, "alice"), bound(2, "bob")), "alice");
        wf.transition(WorkflowState.READY_FOR_SIGNING);
        wf.transition(WorkflowState.WAITING_FOR_SIGNER);

        wf.claimTurn("bob");
        assertThat(wf.currentSigner().userId()).isEqualTo("bob");
        assertThat(wf.maySubmit("bob")).isTrue();
        assertThat(wf.maySubmit("alice")).isFalse();

        // Re-claiming your own turn refreshes it instead of failing.
        assertThat(wf.claimTurn("bob").userId()).isEqualTo("bob");

        // The other signer cannot take the turn while it is held.
        assertThatThrownBy(() -> wf.claimTurn("alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("held by bob");
    }

    @Test
    void flexibleMarkSignedReleasesTheTurn() {
        SigningWorkflowAggregate wf = SigningWorkflowAggregate.create(
                "WF-F3", "doc.pdf", WorkflowSigningPolicy.FLEXIBLE,
                List.of(bound(1, "alice"), bound(2, "bob")), "alice");
        wf.transition(WorkflowState.READY_FOR_SIGNING);
        wf.transition(WorkflowState.WAITING_FOR_SIGNER);

        wf.claimTurn("alice");
        WorkflowSignerRecord next = wf.markSignedByUser("alice", "op-a");
        assertThat(next).isNull(); // flexible: no predefined next signer
        assertThat(wf.currentSigner()).isNull(); // turn released
        assertThat(wf.isEligibleSigner("bob")).isTrue();

        wf.claimTurn("bob");
        wf.markSignedByUser("bob", "op-b");
        assertThat(wf.allSigned()).isTrue();
    }

    @Test
    void sequentialRejectsClaimByNonCurrentSigner() {
        SigningWorkflowAggregate wf = SigningWorkflowAggregate.create(
                "WF-S1", "doc.pdf", WorkflowSigningPolicy.SEQUENTIAL,
                List.of(bound(1, "alice"), bound(2, "bob")), "alice");
        wf.transition(WorkflowState.READY_FOR_SIGNING);
        wf.transition(WorkflowState.WAITING_FOR_SIGNER);
        assertThat(wf.currentSigner().userId()).isEqualTo("alice");
        assertThatThrownBy(() -> wf.claimTurn("bob"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("held by alice");
    }

    /* ── certificate binding (§14/§17) ────────────────────────────────── */

    @Test
    void unboundSlotsBlockRevision0Preparation() {
        SigningWorkflowAggregate wf = SigningWorkflowAggregate.create(
                "WF-B1", "doc.pdf", WorkflowSigningPolicy.SEQUENTIAL,
                List.of(WorkflowSignerRecord.bound(1, "alice", FP_A),
                        WorkflowSignerRecord.unbound(2, "bob")),
                "alice");
        assertThat(wf.allBound()).isFalse();
        assertThat(wf.status()).isEqualTo(WorkflowState.DRAFT);

        wf.bindCertificate("bob", "AB".repeat(32));
        assertThat(wf.allBound()).isTrue();
        assertThat(wf.signers().get(1).isBound()).isTrue();
        assertThat(wf.signers().get(1).boundAt()).isNotNull();
    }

    @Test
    void bindRejectsUnknownUser() {
        SigningWorkflowAggregate wf = SigningWorkflowAggregate.create(
                "WF-B2", "doc.pdf", WorkflowSigningPolicy.SEQUENTIAL,
                List.of(WorkflowSignerRecord.unbound(1, "alice")), "alice");
        assertThatThrownBy(() -> wf.bindCertificate("mallory", "AB".repeat(32)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void persistedClaimSurvivesRehydration() {
        SigningWorkflowAggregate wf = SigningWorkflowAggregate.create(
                "WF-F4", "doc.pdf", WorkflowSigningPolicy.FLEXIBLE,
                List.of(bound(1, "alice"), bound(2, "bob")), "alice");
        wf.transition(WorkflowState.READY_FOR_SIGNING);
        wf.transition(WorkflowState.WAITING_FOR_SIGNER);
        wf.claimTurn("bob");

        SigningWorkflowAggregate.Snapshot snap = wf.snapshot();
        SigningWorkflowAggregate restored = SigningWorkflowAggregate.rehydrate(
                snap.workflowId(), snap.documentName(), snap.policy(),
                snap.signers(), snap.revisions(), snap.audit(),
                snap.status(), snap.blockedReason(),
                snap.claimedTurnUserId(), snap.claimedTurnAt(),
                snap.createdBy());
        assertThat(restored.currentSigner().userId()).isEqualTo("bob");
    }
}

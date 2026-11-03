package com.example.signer.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the local signing workflow domain model: one signer,
 * status-transition legality, and reset after a failure.
 */
class SigningWorkflowTests {

    // ---- shape ---------------------------------------------------------

    @Test
    void aWorkflowHasExactlyOneSigner() {
        SigningWorkflow workflow = SigningWorkflow.create();
        assertThat(workflow.signerCount()).isEqualTo(1);
        assertThat(workflow.getSigners()).hasSize(1);
        assertThat(workflow.getSigners().get(0).getOrder()).isEqualTo(1);
    }

    @Test
    void thereIsNoSecondSigner() {
        SigningWorkflow workflow = SigningWorkflow.create();
        assertThatThrownBy(() -> workflow.getSigner(2))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    // ---- initial state -------------------------------------------------

    @Test
    void theSignerStartsPending() {
        SigningWorkflow workflow = SigningWorkflow.create();
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.PENDING);
        assertThat(workflow.isComplete()).isFalse();
        assertThat(workflow.hasFailed()).isFalse();
    }

    @Test
    void currentSignerIsTheSignerBeforeStart() {
        // The signer is "current" from creation; start() only marks them
        // READY (the transition rules block any early action).
        assertThat(SigningWorkflow.create().getCurrentSigner().getOrder()).isEqualTo(1);
    }

    @Test
    void startReadiesTheSigner() {
        SigningWorkflow workflow = SigningWorkflow.create();
        workflow.start();
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.READY);
    }

    // ---- display names -------------------------------------------------

    @Test
    void displayNameFallsBackToGeneratedLabel() {
        Signer signer = new Signer(2);
        assertThat(signer.getDisplayName()).isEqualTo("Signer 2");
        signer.setDisplayName("  ");
        assertThat(signer.getDisplayName()).isEqualTo("Signer 2");
        signer.setDisplayName("Alice");
        assertThat(signer.getDisplayName()).isEqualTo("Alice");
        assertThat(signer.hasCustomName()).isTrue();
    }

    @Test
    void signerOrderMustBePositive() {
        assertThatThrownBy(() -> new Signer(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- status transitions --------------------------------------------

    @Test
    void theFullLifecycleCompletesTheWorkflow() {
        SigningWorkflow workflow = SigningWorkflow.create();
        workflow.start();

        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(1, SigningStatus.SIGNING);
        workflow.updateSignerStatus(1, SigningStatus.SIGNED);

        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.SIGNED);
        assertThat(workflow.isComplete()).isTrue();
        assertThat(workflow.getCurrentSigner()).as("nothing is left to do").isNull();
        assertThat(WorkflowSigningEngine.signedOrders(workflow)).containsExactly(1);
    }

    @Test
    void theSignerCannotSkipAStep() {
        SigningWorkflow workflow = SigningWorkflow.create();
        // PENDING: no forward transition may start from it but READY.
        assertThatThrownBy(() -> workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> workflow.updateSignerStatus(1, SigningStatus.SIGNED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void illegalBackwardTransitionIsRejected() {
        SigningWorkflow workflow = SigningWorkflow.create();
        workflow.start();
        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        assertThatThrownBy(() -> workflow.updateSignerStatus(1, SigningStatus.READY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> workflow.updateSignerStatus(1, SigningStatus.SIGNED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void repeatingTheSameStatusIsAHarmlessNoOp() {
        SigningWorkflow workflow = SigningWorkflow.create();
        workflow.start();
        workflow.updateSignerStatus(1, SigningStatus.READY);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.READY);
    }

    @Test
    void failingTheSignerMarksTheWorkflowAsFailed() {
        SigningWorkflow workflow = SigningWorkflow.create();
        workflow.start();
        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(1, SigningStatus.FAILED);
        assertThat(workflow.hasFailed()).isTrue();
        assertThat(workflow.isComplete()).isFalse();
    }

    @Test
    void cancellingIsPossibleFromActiveStates() {
        SigningWorkflow workflow = SigningWorkflow.create();
        workflow.start();
        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(1, SigningStatus.CANCELLED);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.CANCELLED);
        assertThat(workflow.hasFailed()).isTrue();
    }

    // ---- reset / retry -------------------------------------------------

    @Test
    void aFailedSignerCanBeResetAndRetried() {
        SigningWorkflow workflow = SigningWorkflow.create();
        workflow.start();
        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(1, SigningStatus.FAILED);

        workflow.resetSigner(1);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.READY);

        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(1, SigningStatus.SIGNING);
        workflow.updateSignerStatus(1, SigningStatus.SIGNED);
        assertThat(workflow.isComplete()).isTrue();
    }

    @Test
    void resettingAHealthySignerIsRejected() {
        SigningWorkflow workflow = SigningWorkflow.create();
        workflow.start();
        assertThatThrownBy(() -> workflow.resetSigner(1))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- helpers -------------------------------------------------------

    @Test
    void statusHelpersReflectTheLifecycle() {
        assertThat(SigningStatus.SIGNED.isDone()).isTrue();
        assertThat(SigningStatus.SIGNING.isActive()).isTrue();
        assertThat(SigningStatus.READY.isActive()).isFalse();
        assertThat(SigningStatus.FAILED.isTerminal()).isTrue();
        assertThat(SigningStatus.PENDING.isTerminal()).isFalse();
    }
}

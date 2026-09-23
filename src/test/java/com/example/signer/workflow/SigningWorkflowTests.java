package com.example.signer.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the multi-signing workflow domain model: mode/signer-count
 * consistency, status-transition legality, sequential ordering and reset.
 */
class SigningWorkflowTests {

    // ---- mode / signer count ------------------------------------------------

    @Test
    void singleModeCreatesExactlyOneSigner() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.SINGLE);
        assertThat(workflow.signerCount()).isEqualTo(1);
        assertThat(workflow.getSigners()).hasSize(1);
    }

    @Test
    void twoSignerModeCreatesExactlyTwoSigners() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.TWO_SIGNERS);
        assertThat(workflow.signerCount()).isEqualTo(2);
        assertThat(workflow.getSigners())
                .extracting(Signer::getOrder)
                .containsExactly(1, 2);
    }

    @Test
    void threeSignerModeCreatesExactlyThreeSigners() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.THREE_SIGNERS);
        assertThat(workflow.signerCount()).isEqualTo(3);
        assertThat(workflow.getSigners())
                .extracting(Signer::getOrder)
                .containsExactly(1, 2, 3);
    }

    @Test
    void selectableModesAreTheThreeUiOptions() {
        assertThat(SigningMode.selectableModes())
                .containsExactly(SigningMode.SINGLE,
                        SigningMode.TWO_SIGNERS, SigningMode.THREE_SIGNERS);
    }

    @Test
    void signerCountsMatchModeNames() {
        assertThat(SigningMode.SINGLE.signerCount()).isEqualTo(1);
        assertThat(SigningMode.TWO_SIGNERS.signerCount()).isEqualTo(2);
        assertThat(SigningMode.THREE_SIGNERS.signerCount()).isEqualTo(3);
        assertThat(SigningMode.SINGLE.isSingle()).isTrue();
        assertThat(SigningMode.TWO_SIGNERS.isMultiSigner()).isTrue();
    }

    // ---- initial state --------------------------------------------------------

    @Test
    void allSignersStartPending() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.THREE_SIGNERS);
        assertThat(workflow.getSigners())
                .extracting(Signer::getStatus)
                .containsOnly(SigningStatus.PENDING);
        assertThat(workflow.isComplete()).isFalse();
        assertThat(workflow.hasFailed()).isFalse();
    }

    @Test
    void currentSignerIsTheFirstSignerBeforeStart() {
        // The first signer is always "current" from creation; start() only
        // marks them READY (the transition rules block any early action).
        assertThat(SigningWorkflow.of(SigningMode.SINGLE).getCurrentSigner().getOrder())
                .isEqualTo(1);
    }

    @Test
    void startReadiesOnlyTheFirstSigner() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.THREE_SIGNERS);
        workflow.start();
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.READY);
        assertThat(workflow.getSigner(2).getStatus()).isEqualTo(SigningStatus.PENDING);
        assertThat(workflow.getSigner(3).getStatus()).isEqualTo(SigningStatus.PENDING);
    }

    // ---- display names --------------------------------------------------------

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

    // ---- status transitions ---------------------------------------------------

    @Test
    void fullSequentialLifecycleForThreeSigners() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.THREE_SIGNERS);
        workflow.start();

        // signer 1 runs the full pipeline
        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(1, SigningStatus.SIGNING);
        workflow.updateSignerStatus(1, SigningStatus.SIGNED);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.SIGNED);

        // the baton passes to signer 2 automatically
        assertThat(workflow.getSigner(2).getStatus()).isEqualTo(SigningStatus.READY);
        assertThat(workflow.getCurrentSigner().getOrder()).isEqualTo(2);

        workflow.updateSignerStatus(2, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(2, SigningStatus.SIGNING);
        workflow.updateSignerStatus(2, SigningStatus.SIGNED);
        assertThat(workflow.getSigner(3).getStatus()).isEqualTo(SigningStatus.READY);

        workflow.updateSignerStatus(3, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(3, SigningStatus.SIGNING);
        workflow.updateSignerStatus(3, SigningStatus.SIGNED);

        assertThat(workflow.isComplete()).isTrue();
        assertThat(workflow.getCurrentSigner()).isNull();
        assertThat(WorkflowSigningEngine.signedOrders(workflow)).containsExactly(1, 2, 3);
    }

    @Test
    void signerCannotBeReadyWhileEarlierSignerStillPending() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.THREE_SIGNERS);
        workflow.start();
        assertThatThrownBy(() -> workflow.updateSignerStatus(2, SigningStatus.READY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");
        assertThatThrownBy(() -> workflow.updateSignerStatus(3, SigningStatus.READY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void signerCannotSignOutOfOrder() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.TWO_SIGNERS);
        workflow.start();
        // signer 2 is PENDING: no forward transition may start from it
        assertThatThrownBy(() ->
                workflow.updateSignerStatus(2, SigningStatus.AUTHENTICATING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> workflow.updateSignerStatus(2, SigningStatus.SIGNED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void illegalBackwardTransitionIsRejected() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.SINGLE);
        workflow.start();
        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        assertThatThrownBy(() -> workflow.updateSignerStatus(1, SigningStatus.READY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> workflow.updateSignerStatus(1, SigningStatus.SIGNED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failingASignerMarksTheWorkflowAsFailed() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.TWO_SIGNERS);
        workflow.start();
        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(1, SigningStatus.FAILED);
        assertThat(workflow.hasFailed()).isTrue();
        assertThat(workflow.isComplete()).isFalse();
    }

    @Test
    void cancellingIsPossibleFromActiveStates() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.SINGLE);
        workflow.start();
        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(1, SigningStatus.CANCELLED);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.CANCELLED);
    }

    // ---- reset / retry --------------------------------------------------------

    @Test
    void failedSignerCanBeResetAndRetried() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.TWO_SIGNERS);
        workflow.start();
        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(1, SigningStatus.FAILED);

        workflow.resetSigner(1);
        assertThat(workflow.getSigner(1).getStatus()).isEqualTo(SigningStatus.READY);

        workflow.updateSignerStatus(1, SigningStatus.AUTHENTICATING);
        workflow.updateSignerStatus(1, SigningStatus.SIGNING);
        workflow.updateSignerStatus(1, SigningStatus.SIGNED);
        assertThat(workflow.getSigner(2).getStatus()).isEqualTo(SigningStatus.READY);
    }

    @Test
    void resettingAHealthySignerIsRejected() {
        SigningWorkflow workflow = SigningWorkflow.of(SigningMode.SINGLE);
        workflow.start();
        assertThatThrownBy(() -> workflow.resetSigner(1))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- helpers ----------------------------------------------------------------

    @Test
    void statusHelpersReflectTheLifecycle() {
        assertThat(SigningStatus.SIGNED.isDone()).isTrue();
        assertThat(SigningStatus.SIGNING.isActive()).isTrue();
        assertThat(SigningStatus.READY.isActive()).isFalse();
        assertThat(SigningStatus.FAILED.isTerminal()).isTrue();
        assertThat(SigningStatus.PENDING.isTerminal()).isFalse();
    }

    @Test
    void workflowRejectsNullMode() {
        assertThatCode(() -> new SigningWorkflow(null))
                .isInstanceOf(NullPointerException.class);
    }
}

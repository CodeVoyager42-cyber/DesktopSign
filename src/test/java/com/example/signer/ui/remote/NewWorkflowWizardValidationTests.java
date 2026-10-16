package com.example.signer.ui.remote;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The wizard's client-side validation mirrors the server rules (email shape,
 * fingerprint hex, uniqueness) — the user should hear about a problem before
 * the request ever leaves the machine.
 */
class NewWorkflowWizardValidationTests {

    @Test
    void validMixedSignersPass() {
        String problem = NewWorkflowWizard.validateSigners(
                List.of("john@example.com", "alice"),
                List.of("", ""));
        assertThat(problem).isNull();
    }

    @Test
    void atLeastOneSignerIsRequired() {
        assertThat(NewWorkflowWizard.validateSigners(List.of(), List.of()))
                .contains("at least one signer");
    }

    @Test
    void blankIdentifierIsRejected() {
        assertThat(NewWorkflowWizard.validateSigners(
                List.of("john@example.com", "   "), List.of("", "")))
                .contains("Signer 2");
    }

    @Test
    void malformedEmailIsRejected() {
        assertThat(NewWorkflowWizard.validateSigners(
                List.of("john@@example"), List.of("")))
                .contains("not a valid email address");
    }

    @Test
    void shortFingerprintIsRejected() {
        assertThat(NewWorkflowWizard.validateSigners(
                List.of("alice"), List.of("abc123")))
                .contains("64 hex characters");
    }

    @Test
    void colonSeparatedFingerprintIsNormalized() {
        String hex = "ab".repeat(32);
        String colonSeparated = String.join(":", hex.split("(?<=\\G.{2})"));
        assertThat(NewWorkflowWizard.validateSigners(
                List.of("alice"), List.of(colonSeparated))).isNull();
    }

    @Test
    void duplicateSignersAreRejected() {
        assertThat(NewWorkflowWizard.validateSigners(
                List.of("alice", "ALICE "), List.of("", "")))
                .contains("unique");
    }
}

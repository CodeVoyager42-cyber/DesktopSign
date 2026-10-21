package com.example.signer.server.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which requests the verified-address requirement covers (security Phase D1,
 * risk D4). The policy only ever adds a requirement, so the risk of a miss is
 * a signing action slipping through — hence the spellings below.
 */
class SigningActionPolicyTests {

    @Test
    void theFourSigningActionsAreCovered() {
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/claim")).isTrue();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/signatures")).isTrue();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/launch-codes")).isTrue();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/signers/alice/certificate")).isTrue();
    }

    @Test
    void readingIsNotCovered() {
        // The decision: an unverified account may sign in and look around.
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/WF-1/task")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/WF-1/document")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/my-tasks")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/WF-1/audit")).isFalse();
    }

    @Test
    void theMethodMustBePost() {
        // A GET of a covered path is not a signing action.
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/WF-1/claim")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "DELETE", "/api/workflows/WF-1/signatures")).isFalse();
    }

    @Test
    void unrelatedAndMalformedPathsAreNotCovered() {
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/cancel")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/invitations")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/auth/login")).isFalse();
        // Anchored: no prefix or suffix may smuggle a path past it.
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/claim/extra")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/x/api/workflows/WF-1/claim")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/signers/a/b/certificate")).isFalse();
    }

    @Test
    void nullsAreNotCovered() {
        assertThat(SigningActionPolicy.requiresVerifiedEmail(null, "/api/workflows/WF-1/claim"))
                .isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail("POST", null)).isFalse();
    }
}

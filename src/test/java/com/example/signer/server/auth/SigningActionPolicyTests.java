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
    void theFiveSigningActionsAreCovered() {
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/claim")).isTrue();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/WF-1/document")).isTrue();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/signatures")).isTrue();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/launch-codes")).isTrue();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/signers/alice/certificate")).isTrue();
    }

    @Test
    void lookingAroundIsNotCovered() {
        // The decision: an unverified account may sign in and see where it
        // stands. The document is the exception, because it is step one of
        // signing rather than a way of looking around.
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/WF-1/task")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/my-tasks")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/WF-1/audit")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/WF-1/revisions")).isFalse();
    }

    @Test
    void theMethodIsPairedWithTheAction() {
        // A covered path reached with another method is not a signing action,
        // so no method confusion can slip past the pairing.
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "GET", "/api/workflows/WF-1/claim")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "DELETE", "/api/workflows/WF-1/signatures")).isFalse();
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "POST", "/api/workflows/WF-1/document")).isFalse();
        // HEAD is the exception: Spring serves it from the GET handler, so it
        // must not be a way round the download gate.
        assertThat(SigningActionPolicy.requiresVerifiedEmail(
                "HEAD", "/api/workflows/WF-1/document")).isTrue();
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

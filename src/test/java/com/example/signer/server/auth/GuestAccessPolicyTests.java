package com.example.signer.server.auth;

import com.example.signer.server.auth.JwtService.GuestAuthentication;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GuestAccessPolicyTests {

    private final GuestAuthentication guest = new GuestAuthentication(
            "mouad", "WF-1", 1, 7L, "jti", Instant.now().plusSeconds(600));

    @Test
    void onlyTheSigningTaskRequestsOfItsOwnWorkflowAreAllowed() {
        assertThat(GuestAccessPolicy.allows("GET", "/api/workflows/WF-1/task", guest)).isTrue();
        assertThat(GuestAccessPolicy.allows("POST", "/api/workflows/WF-1/claim", guest)).isTrue();
        assertThat(GuestAccessPolicy.allows("GET", "/api/workflows/WF-1/document", guest)).isTrue();
        assertThat(GuestAccessPolicy.allows("POST", "/api/workflows/WF-1/signatures", guest)).isTrue();
        assertThat(GuestAccessPolicy.allows("POST",
                "/api/workflows/WF-1/signers/mouad/certificate", guest)).isTrue();
    }

    @Test
    void everythingElseIsRefused() {
        for (String[] r : new String[][]{
                {"GET", "/api/workflows/WF-2/task"},
                {"GET", "/api/workflows/WF-10/task"},
                {"GET", "/api/workflows/WF%2D1/task"},
                {"GET", "/api/workflows/WF-1/task/"},
                {"POST", "/api/workflows/WF-1/task"},
                {"GET", "/api/workflows/WF-1/signatures"},
                {"GET", "/api/workflows/WF-1"},
                {"GET", "/api/workflows/WF-1/audit"},
                {"GET", "/api/workflows/WF-1/revisions"},
                {"POST", "/api/workflows/WF-1/cancel"},
                {"POST", "/api/workflows/WF-1/invitations"},
                {"POST", "/api/workflows/WF-1/signers/alice/certificate"},
                {"GET", "/api/workflows/signing-tasks"},
                {"GET", "/api/workflows/my-tasks"},
                {"POST", "/api/workflows"}}) {
            assertThat(GuestAccessPolicy.allows(r[0], r[1], guest)).as(r[0] + " " + r[1]).isFalse();
        }
        assertThat(GuestAccessPolicy.allows("GET", "/api/workflows/WF-1/task", null)).isFalse();
    }
}

package com.example.signer.remote;

import com.example.signer.server.api.ApiStates.InvitationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationsCardTests {

    @Test
    void anAcceptedInvitationNeverLooksSigned() {
        assertThat(InvitationsCard.badgeClassFor(InvitationStatus.ACCEPTED)).isEqualTo("badge--waiting");
        assertThat(InvitationsCard.badgeClassFor(InvitationStatus.COMPLETED)).isEqualTo("badge--signed");
        assertThat(InvitationsCard.badgeClassFor(InvitationStatus.EXPIRED)).isEqualTo("badge--expired");
        assertThat(InvitationsCard.badgeClassFor(InvitationStatus.REVOKED)).isEqualTo("badge--revoked");
        assertThat(InvitationsCard.badgeClassFor(InvitationStatus.UNKNOWN)).isEqualTo("badge--waiting");
    }

    @Test
    void anUnreadableDateIsShownAsIs() {
        assertThat(InvitationsCard.shortDate("not a date")).isEqualTo("not a date");
        assertThat(InvitationsCard.shortDate("2026-03-04T17:20:00Z")).isNotBlank();
    }
}

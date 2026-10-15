package com.example.signer.remote;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * After creating or resending an invitation the app shows the exact link
 * that was emailed. The full URL is long, so it is shortened for display —
 * but the parts a sender needs to recognise a link (the base address and
 * the token tail) must survive, and short links must not be mangled.
 *
 * <p>Pure string handling, so it is testable without a JavaFX toolkit.
 */
class SentInvitationLinkDisplayTests {

    private static final String LAN_LINK =
            "http://192.168.11.106:8080/invitations/"
                    + "GXd74dP6ZkrgrriB461WfKxyvNnmte1oE_GqKK4dMTg";

    @Test
    void aShortLinkIsShownInFull() {
        String shortLink = "http://192.168.11.106:8080/invitations/abc123";

        assertThat(NewWorkflowWizard.shortenLink(shortLink))
                .isEqualTo(shortLink);
    }

    @Test
    void aLongLinkKeepsTheBaseAddressAndTheTokenTail() {
        assertThat(LAN_LINK.length()).isGreaterThan(72);

        String shown = NewWorkflowWizard.shortenLink(LAN_LINK);

        assertThat(shown).startsWith("http://192.168.11.106:8080/invitat");
        assertThat(shown).endsWith(LAN_LINK.substring(LAN_LINK.length() - 24));
        assertThat(shown).contains("…");
        assertThat(shown.length()).isLessThan(LAN_LINK.length());
    }

    @Test
    void shorteningNeverThrowsOnAnAbsentLink() {
        assertThat(NewWorkflowWizard.shortenLink(null)).isNotNull();
    }
}

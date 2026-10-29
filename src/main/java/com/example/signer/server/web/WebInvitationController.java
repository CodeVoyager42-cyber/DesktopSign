package com.example.signer.server.web;

import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.invites.InvitationService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two things an owner does to an invitation after sending it (Phase E
 * part 2): send it again, or kill it.
 *
 * <p>Separate from {@code WebWorkflowController} only because the path is:
 * an invitation is addressed by its own id, not through the workflow it
 * belongs to — the same shape the desktop's {@code /api/invitations} has.
 *
 * <p><b>The id is not a capability.</b> Both routes take a numeric
 * invitation id, which is guessable, and both pass the caller's username to
 * {@link InvitationService}, which re-checks that this account created the
 * workflow the invitation belongs to. Knowing or guessing an id grants
 * nothing; that is asserted, not assumed.
 *
 * <p>Only the website's own sign-up and accept flows are public. These are
 * not: {@code /web/api/**} requires a session and, as POSTs, a CSRF token.
 */
@RestController
@RequestMapping("/web/api/invitations")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
public class WebInvitationController {

    private final InvitationService invitations;

    public WebInvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    /**
     * Invalidates the old token, then issues and e-mails a new one.
     *
     * <p>The answer carries the link that just went out, so the sender can
     * see what the recipient will get. It is not reproducible afterwards —
     * the database keeps only the token's hash — which is why it is returned
     * here rather than fetched later.
     */
    @PostMapping("/{invitationId}/resend")
    public ResponseEntity<InvitationLinkView> resend(@PathVariable Long invitationId,
                                                     Authentication caller) {
        return ResponseEntity.ok(this.invitations.resend(invitationId, caller.getName()));
    }

    /** Ends the invitation: the link stops working, the slot stays unsigned. */
    @PostMapping("/{invitationId}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable Long invitationId,
                                       Authentication caller) {
        this.invitations.revoke(invitationId, caller.getName());
        return ResponseEntity.noContent().build();
    }
}

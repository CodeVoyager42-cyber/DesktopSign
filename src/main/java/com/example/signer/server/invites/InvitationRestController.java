package com.example.signer.server.invites;

import com.example.signer.server.api.ApiContracts;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.example.signer.server.api.ApiContracts.InvitationLinkView;
import com.example.signer.server.api.ApiContracts.InvitationPreviewView;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.auth.AuthController;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.entity.WorkflowEntity;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.db.repository.WorkflowSignerRepository;
import com.example.signer.server.db.entity.WorkflowSignerEntity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * Invitation endpoints (brief §38). {@code GET /api/invitations/{token}} is
 * public (the token IS the capability — only its hash is stored);
 * accept/decline require a valid account whose email matches the invitation;
 * resend/revoke are workflow-owner operations.
 */
@RestController
@RequestMapping("/api/invitations")
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class InvitationRestController {

    private static final Logger log =
            LoggerFactory.getLogger(InvitationRestController.class);

    private final InvitationService invitations;
    private final WorkflowRepository workflows;
    private final WorkflowSignerRepository signers;
    private final UserRepository users;
    private final JwtService jwtService;
    /** Longest a guest signing session lasts (the invitation expiry may cut it shorter). */
    private Duration guestSessionTtl = Duration.ofMinutes(60);

    @Value(
            "${signature.remote-server.guest-session-minutes:60}")
    void setGuestSessionMinutes(long minutes) {
        this.guestSessionTtl = Duration.ofMinutes(Math.max(1, minutes));
    }

    public InvitationRestController(InvitationService invitations,
                                    WorkflowRepository workflows,
                                    WorkflowSignerRepository signers,
                                    UserRepository users,
                                    JwtService jwtService) {
        this.invitations = invitations;
        this.workflows = workflows;
        this.signers = signers;
        this.users = users;
        this.jwtService = jwtService;
    }

    /** Public invitation preview (brief §43): what the signer sees first. */
    @GetMapping("/{token}")
    public InvitationPreviewView preview(@PathVariable String token) {
        InvitationEntity invitation;
        try {
            invitation = invitations.redeemableByToken(token);
        } catch (ApiException e) {
            // Expired/used/revoked invitations still render a friendly view
            // (brief §44) — but only when the token hash is even known. A
            // used invitation whose slot is already signed is "completed".
            if (e.code() == ApiErrorCode.INVITATION_ALREADY_USED
                    && invitations.isSlotSignedForToken(token)) {
                throw new ApiException(ApiErrorCode.INVITATION_COMPLETED,
                        "Your signature for this invitation is already recorded");
            }
            if (e.code() != ApiErrorCode.INVITATION_ALREADY_USED) {
                throw e;
            }
            // Opened before but not signed yet (the signer closed the app
            // half-way): the same link may be opened again until it is
            // signed or expires — exactly what openGuestSession allows.
            invitation = invitations.reopenableByToken(token);
        } catch (NoSuchElementException e) {
            throw new ApiException(ApiErrorCode.INVITATION_NOT_FOUND,
                    "Invitation not found");
        }
        WorkflowEntity wf = workflows.findById(invitation.getWorkflowId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.WORKFLOW_NOT_FOUND,
                        "Workflow not found"));
        // Whether the recipient already has a REGISTERED account is decided
        // by the INVITED ADDRESS itself — never by whoever happens to occupy
        // the signer slot. Walking the slot's account is what made a stranger
        // invited into an occupied slot look "already registered".
        boolean accountExists = users.findByEmail(invitation.getEmail())
                .map(UserEntity::isActive)
                .orElse(false);
        log.info("Invitation opened for workflow {} signer {} (recipient {},"
                        + " registered={})", wf.getWorkflowId(),
                invitation.getSignerOrder(), invitation.getEmail(), accountExists);
        return new InvitationPreviewView(wf.getWorkflowId(), wf.getDocumentName(),
                wf.getCreatedBy(), invitation.getEmail(), invitation.getStatus(),
                invitation.getExpiresAt().toString(), accountExists);
    }

    /**
     * Opens a GUEST signing session from the invitation link: the invited
     * signer needs no SignFlow account and sends no credentials — the
     * token in the link is validated server-side (hash lookup, expiry,
     * revocation, slot not yet signed). The returned access token is scoped
     * to this one signing task (see GuestAccessPolicy). This authorizes
     * access only; the digital signature is made later with the signer's
     * own certificate.
     */
    @PostMapping("/{token}/guest-session")
    public ResponseEntity<ApiContracts.GuestSessionView>
            guestSession(@PathVariable String token) {
        InvitationService.GuestSessionGrant grant = invitations.openGuestSession(token);
        String accessToken = jwtService.issueGuestJwt(grant.principal(), grant.workflowId(),
                grant.signerOrder(), grant.invitationId(), grant.invitationExpiresAt(),
                guestSessionTtl);
        JwtService.GuestAuthentication issued = jwtService.validateGuestJwt(accessToken);
        long expiresIn = Math.max(0, issued.expiresAt().getEpochSecond()
                - Instant.now().getEpochSecond());
        return ResponseEntity.ok(new ApiContracts.GuestSessionView(
                accessToken, "Bearer", expiresIn, grant.workflowId(), grant.documentName(),
                grant.requestedBy(), grant.signerOrder(), grant.principal(), grant.email(),
                grant.invitationExpiresAt() == null ? null : grant.invitationExpiresAt().toString()));
    }

    /** Existing-account acceptance: verifies the account email matches. */    /** Existing-account acceptance: verifies the account email matches. */
    @PostMapping("/{token}/accept")
    public ResponseEntity<InvitationPreviewView> accept(
            @PathVariable String token,
            @RequestHeader(value = "Authorization", required = false) String header) {
        String user = authenticatedUser(header);
        WorkflowEntity wf = invitations.accept(token, user);
        return ResponseEntity.ok(new InvitationPreviewView(wf.getWorkflowId(),
                wf.getDocumentName(), wf.getCreatedBy(), null,
                InvitationEntity.STATUS_ACCEPTED, null, true));
    }

    /**
     * Claims the invitation as the authenticated recipient (brief §46):
     * full server-side validation, then a participant-scoped signing
     * context. Idempotent for the same participant.
     */
    @PostMapping("/{token}/claim")
    public ResponseEntity<ApiContracts
            .SigningSessionView> claim(
            @PathVariable String token,
            @RequestHeader(value = "Authorization", required = false) String header) {
        String user = authenticatedUser(header);
        return ResponseEntity.ok(invitations.claim(token, user));
    }

    /** Declines the invitation (the slot stays unsigned; owner can resend). */
    @PostMapping("/{token}/decline")
    public ResponseEntity<Void> decline(
            @PathVariable String token,
            @RequestHeader(value = "Authorization", required = false) String header) {
        String user = authenticatedUser(header);
        invitations.decline(token, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Owner operation: invalidate the old token, issue and email a new one.
     * Returns the freshly emailed link so the sender can see what went out;
     * it is not reproducible later (only the token hash is stored).
     */
    @PostMapping("/{invitationId}/resend")
    public ResponseEntity<InvitationLinkView> resend(
            @PathVariable Long invitationId,
            @RequestHeader(value = "Authorization", required = false) String header) {
        String user = authenticatedUser(header);
        return ResponseEntity.ok(invitations.resend(invitationId, user));
    }

    /** Owner operation: kill the invitation. */
    @PostMapping("/{invitationId}/revoke")
    public ResponseEntity<Void> revoke(
            @PathVariable Long invitationId,
            @RequestHeader(value = "Authorization", required = false) String header) {
        String user = authenticatedUser(header);
        invitations.revoke(invitationId, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Mandatory authentication for the invitation mutations. A missing,
     * expired or forged access token answers 401 AUTHENTICATION_REQUIRED —
     * not 403 — so the desktop client refreshes its session and retries
     * exactly like it does for the workflow API.
     */
    private String authenticatedUser(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(ApiErrorCode.AUTHENTICATION_REQUIRED,
                    "Authentication required");
        }
        try {
            return jwtService.validateAccessJwt(header.substring(7).trim()).subject();
        } catch (JwtService.JwtValidationException e) {
            throw new ApiException(ApiErrorCode.AUTHENTICATION_REQUIRED,
                    "Authentication required");
        }
    }
}

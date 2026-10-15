package com.example.signer.server.invites;

import com.example.signer.server.api.ApiContracts;
import com.example.signer.server.auth.JwtService;
import com.example.signer.server.db.AuditLog;
import java.util.Base64;
import com.example.signer.server.WorkflowState;
import com.example.signer.server.api.ApiStates.EmailStatus;
import com.example.signer.server.AuditEventType;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.entity.WorkflowEntity;
import com.example.signer.server.db.repository.AuditEventRepository;
import com.example.signer.server.db.entity.AuditEventEntity;
import com.example.signer.server.db.repository.InvitationRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.repository.WorkflowSignerRepository;
import com.example.signer.server.db.entity.WorkflowSignerEntity;
import com.example.signer.server.db.repository.WorkflowRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Invitation lifecycle (brief Phase 4/§13-§17): creation, secure token
 * handling (only SHA-256 hashes persisted — raw tokens are 256-bit
 * SecureRandom values delivered exactly once), expiration, resend (old token
 * invalidated), revocation, and single-use acceptance bound to the invited
 * email.
 */
@Service
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final InvitationRepository invitations;
    private final WorkflowRepository workflows;
    private final UserRepository users;
    private final WorkflowSignerRepository signers;
    private final AuditEventRepository auditEvents;
    private final EmailService emailService;
    private final InvitationLinkResolver links;
    private final AuditLog auditLog;
    private final SecureRandom random = new SecureRandom();
    private final Duration expiration;
    private final String deepLinkPrefix;

    public InvitationService(InvitationRepository invitations,
                             WorkflowRepository workflows,
                             UserRepository users,
                             WorkflowSignerRepository signers,
                             AuditEventRepository auditEvents,
                             EmailService emailService,
                             InvitationLinkResolver links,
                             AuditLog auditLog,
                             @Value("${signature.remote-server.invitation.expiration:PT48H}")
                             Duration expiration,
                             @Value("${signature.remote-server.invitation.url-prefix:signflow://invitation/}")
                             String deepLinkPrefix) {
        this.invitations = invitations;
        this.workflows = workflows;
        this.users = users;
        this.signers = signers;
        this.auditEvents = auditEvents;
        this.emailService = emailService;
        this.links = links;
        this.auditLog = auditLog;
        this.expiration = expiration;
        this.deepLinkPrefix = deepLinkPrefix;
    }

    /**
     * Canonical invitation URL emailed to the signer (brief §9):
     * {@code <resolved base>/invitations/<token>}. This is an ENTRY POINT —
     * it validates the invitation and hands off to the desktop app — never
     * the signing UI itself. The token is the only secret in the URL; no
     * database id is exposed.
     *
     * <p>The base URL is chosen by {@link InvitationLinkResolver} so the
     * link points at an address the SIGNER can actually reach, not at
     * {@code localhost}.
     */
    public String invitationUrl(String rawToken) {
        return links.baseUrl() + "/invitations/" + rawToken;
    }

    /** Base URL the emailed links are built from (see the resolver). */
    public InvitationLinkResolver linkResolver() {
        return links;
    }

    /** Desktop deep link that opens the app straight onto this invitation. */
    public String deepLink(String rawToken) {
        return deepLinkPrefix + rawToken;
    }

    /**
     * The invitation row was written, but its email could not be delivered.
     *
     * <p>Deliberately its own exception type: {@link #invite} and
     * {@link #resend} declare it in {@code noRollbackFor}, so the
     * transaction still COMMITS when delivery fails. Rolling back would
     * erase the very row the owner needs in order to retry — the invitation
     * would disappear while the only remaining trace of a token nobody ever
     * received was its audit event. The caller still receives
     * {@code EMAIL_DELIVERY_FAILED} with an actionable message.
     */
    public static class EmailDeliveryException extends ApiException {
        public EmailDeliveryException(String message) {
            super(ApiErrorCode.EMAIL_DELIVERY_FAILED, message);
        }
    }

    /**
     * Creates (or supersedes) the invitation for one signer slot and sends
     * the email. Any still-pending invitation for the slot is revoked first
     * (brief §40: at most one live token per signer).
     *
     * <p>A delivery failure does NOT roll the invitation back: the row is
     * committed with {@code email_status = FAILED} so the owner can hit
     * Resend (see {@link EmailDeliveryException}).
     *
     * @return the RAW invitation token — never persisted, delivered once
     */
    @Transactional(noRollbackFor = InvitationService.EmailDeliveryException.class)
    public String invite(String workflowPublicId, int signerOrder, String email,
                         String invitedBy) {
        // Lock the workflow row FIRST: this method updates the signer row
        // (invited_at) and appends audit events; taking the same row lock as
        // the aggregate store keeps lock order identical (no deadlock) and
        // serialises with concurrent workflow saves in every server process.
        WorkflowEntity wf = workflows.lockByWorkflowId(workflowPublicId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.WORKFLOW_NOT_FOUND,
                        "Workflow not found"));
        requireCreator(wf, invitedBy);
        String normalizedEmail = UserEntity.normalizeEmail(email);
        if (normalizedEmail == null || !normalizedEmail.matches(".+@.+\\..+")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "A valid email address is required for the invitation");
        }

        // The invitation is the SIGNER SLOT's identity, not a free-form
        // address: an invite can only be addressed to the person the slot is
        // assigned to. Otherwise a slot could be handed to a stranger while
        // its real owner signs, and the recipient would be told they already
        // have an account (the slot's) — the exact state confusion this
        // guards against.
        WorkflowSignerEntity slot = requireSlot(wf, signerOrder);
        UserEntity slotAccount = users.findByUsername(slot.getUsername())
                .orElseThrow(() -> new ApiException(
                        ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "The invited signer slot no longer has an account"));
        String slotEmail = slotAccount.getEmail() == null ? null
                : UserEntity.normalizeEmail(slotAccount.getEmail());
        if (slotEmail == null || !slotEmail.equals(normalizedEmail)) {
            throw new ApiException(ApiErrorCode.EMAIL_MISMATCH,
                    slotEmail == null
                            ? "Signer " + signerOrder + " has no email address"
                                    + " on file — add an account with an email"
                                    + " address, or create the workflow with"
                                    + " this address as the signer"
                            : "Signer " + signerOrder + " is assigned to "
                                    + slotEmail + " — an invitation can only be"
                                    + " sent to that address");
        }

        // Bulk revoke FIRST (flushed immediately) so the partial unique
        // index "one PENDING per slot" never sees two pending rows when the
        // replacement invitation is inserted (Hibernate flushes inserts
        // before updates).
        invitations.revokePending(wf.getId(), signerOrder);

        InvitationEntity invitation = new InvitationEntity();
        invitation.setWorkflowId(wf.getId());
        invitation.setSignerOrder(signerOrder);
        invitation.setEmail(normalizedEmail);
        invitation.setTokenHash("");
        invitation.setInvitedBy(invitedBy);
        invitation.setExpiresAt(Instant.now().plus(expiration));

        String rawToken = newToken();
        invitation.setTokenHash(hash(rawToken));
        invitations.save(invitation);
        slot.setInvitedAt(Instant.now());
        signers.save(slot);

        appendAudit(wf, AuditEventType.INVITATION_CREATED, invitedBy,
                "Invitation created for signer " + signerOrder + " (" + normalizedEmail + ")");
        log.info("Invitation created for workflow {} signer {} (recipient {},"
                        + " registered={}) — nothing is signed by this",
                wf.getWorkflowId(), signerOrder, normalizedEmail,
                slotAccount.isActive());
        sendEmail(invitation, wf, rawToken);
        return rawToken;
    }

    /**
     * Mutation protocol for invitations (P0.4): lock the owning workflow row
     * FIRST — the same lock {@code invite()}, the audit log and the
     * aggregate store take, so the lock order is identical everywhere —
     * and only then read the invitation. The id lookup is a scalar query,
     * so the entity is loaded after the lock and reflects every committed
     * change (a revoke can no longer be overwritten by a claim that read the
     * row before the revoke committed).
     */
    private InvitationEntity lockedByToken(String rawToken) {
        String tokenHash = hash(rawToken);
        Long workflowDbId = invitations.findWorkflowIdByTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(ApiErrorCode.INVITATION_NOT_FOUND,
                        "Invitation not found"));
        workflows.lockById(workflowDbId);
        return invitations.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(ApiErrorCode.INVITATION_NOT_FOUND,
                        "Invitation not found"));
    }

    /** As {@link #lockedByToken}, by invitation id (owner operations). */
    private InvitationEntity lockedById(Long invitationId) {
        Long workflowDbId = invitations.findWorkflowIdById(invitationId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.INVITATION_NOT_FOUND,
                        "Invitation not found"));
        workflows.lockById(workflowDbId);
        return invitations.findById(invitationId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.INVITATION_NOT_FOUND,
                        "Invitation not found"));
    }

    /**
     * Whether the signer slot of the invitation behind {@code rawToken} is
     * already signed (read-only; used to tell "already used" from
     * "completed" on the invitation screen). False for unknown tokens.
     */
    @Transactional(readOnly = true)
    public boolean isSlotSignedForToken(String rawToken) {
        return invitations.findByTokenHash(hash(rawToken))
                .flatMap(inv -> signers.findByWorkflowIdOrderBySignerOrder(inv.getWorkflowId())
                        .stream()
                        .filter(s -> s.getSignerOrder() == inv.getSignerOrder())
                        .findFirst())
                .map(WorkflowSignerEntity::isSigned)
                .orElse(false);
    }

    /** The signer slot at {@code signerOrder}, or a typed 400. */
    private WorkflowSignerEntity requireSlot(WorkflowEntity wf, int signerOrder) {
        return signers.findByWorkflowIdOrderBySignerOrder(wf.getId()).stream()
                .filter(s -> s.getSignerOrder() == signerOrder)
                .findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.BAD_REQUEST,
                        "Signer " + signerOrder + " is not part of this workflow"));
    }

    /** Owner-facing per-slot invitation status for workflow tracking. */
    @Transactional(readOnly = true)
    public List<ApiContracts.InvitationStatusView>
            listForWorkflow(String workflowPublicId, String requester,
                            Map<Integer, Boolean> slotSigned) {
        WorkflowEntity wf = requireWorkflow(workflowPublicId);
        requireCreator(wf, requester);

        // One row per signer slot: the live PENDING invitation if one
        // exists, otherwise the most recent historical row (resend/revoke
        // supersede but keep the old rows for the audit trail).
        Map<Integer, InvitationEntity> current = new HashMap<>();
        for (InvitationEntity inv : invitations.findByWorkflowId(wf.getId())) {
            InvitationEntity existing = current.get(inv.getSignerOrder());
            if (existing == null || isNewer(inv, existing)) {
                current.put(inv.getSignerOrder(), inv);
            }
        }

        Instant now = Instant.now();
        return current.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    InvitationEntity inv = entry.getValue();
                    String status = displayStatus(inv, now);
                    return new ApiContracts
                            .InvitationStatusView(
                            inv.getId(), inv.getSignerOrder(), inv.getEmail(),
                            status, inv.getEmailStatus(),
                            inv.getCreatedAt() == null ? null
                                    : inv.getCreatedAt().toString(),
                            inv.getExpiresAt() == null ? null
                                    : inv.getExpiresAt().toString(),
                            inv.getAcceptedAt() == null ? null
                                    : inv.getAcceptedAt().toString(),
                            Boolean.TRUE.equals(slotSigned
                                    .get(inv.getSignerOrder())));
                })
                .toList();
    }

    /**
     * Latest invitation state for ONE participant slot, as seen by the
     * signing-tasks workspace (any participant may query their own slot).
     * Returns {@code null} when the slot has no invitation history — such
     * slots are governed purely by the workflow lifecycle.
     */
    public record SlotInvitationState(String status, String expiresAt) {
    }

    @Transactional(readOnly = true)
    public SlotInvitationState latestSlotState(String workflowPublicId,
                                               String username) {
        if (workflowPublicId == null || username == null) {
            return null;
        }
        WorkflowEntity wf = workflows.findByWorkflowId(workflowPublicId)
                .orElse(null);
        if (wf == null) {
            return null;
        }
        Integer slotOrder = signers.findByWorkflowIdOrderBySignerOrder(wf.getId())
                .stream()
                .filter(s -> username.equals(s.getUsername()))
                .map(WorkflowSignerEntity::getSignerOrder)
                .findFirst().orElse(null);
        if (slotOrder == null) {
            return null;
        }
        InvitationEntity latest = latestForSlot(wf.getId(), slotOrder);
        if (latest == null) {
            return null;
        }
        // EXPIRED is derived lazily for display (the row itself stays PENDING
        // until it is touched) — the same rule the tracking view uses.
        String status = displayStatus(latest, Instant.now());
        return new SlotInvitationState(status,
                latest.getExpiresAt() == null ? null
                        : latest.getExpiresAt().toString());
    }

    /**
     * The current invitation of one slot (live pending row first, then the
     * newest), reading only that slot's rows.
     */
    private InvitationEntity latestForSlot(Long workflowDbId, int signerOrder) {
        InvitationEntity latest = null;
        for (InvitationEntity inv : invitations.findByWorkflowIdAndSignerOrder(
                workflowDbId, signerOrder)) {
            if (latest == null || isNewer(inv, latest)) {
                latest = inv;
            }
        }
        return latest;
    }

    /** Prefers the live pending row, then the most recently created. */
    private static boolean isNewer(InvitationEntity candidate,
                                   InvitationEntity incumbent) {
        boolean candidatePending =
                InvitationEntity.STATUS_PENDING.equals(candidate.getStatus());
        boolean incumbentPending =
                InvitationEntity.STATUS_PENDING.equals(incumbent.getStatus());
        if (candidatePending != incumbentPending) {
            return candidatePending;
        }
        return candidate.getId() != null && incumbent.getId() != null
                && candidate.getId() > incumbent.getId();
    }

    /** Lazily derives EXPIRED for display without mutating the row. */
    private static String displayStatus(InvitationEntity inv, Instant now) {
        if (InvitationEntity.STATUS_PENDING.equals(inv.getStatus())
                && inv.getExpiresAt() != null && inv.getExpiresAt().isBefore(now)) {
            return InvitationEntity.STATUS_EXPIRED;
        }
        return inv.getStatus();
    }

    /**
     * Resends an invitation: the old token is invalidated, a new one is
     * issued with a fresh expiry (brief §40). Only the workflow creator may
     * resend.
     *
     * <p>A delivery failure does NOT roll the replacement back: the revoked
     * old row and the new {@code email_status = FAILED} row are committed
     * together, so Resend can be tried again (see
     * {@link EmailDeliveryException}).
     *
     * @return the freshly emailed link, so the sender can see what went out
     */
    @Transactional(noRollbackFor = InvitationService.EmailDeliveryException.class)
    public ApiContracts.InvitationLinkView
            resend(Long invitationId, String requestedBy) {
        InvitationEntity invitation = lockedById(invitationId);
        WorkflowEntity wf = requireWorkflowEntity(invitation.getWorkflowId());
        requireCreator(wf, requestedBy);
        if (InvitationEntity.STATUS_ACCEPTED.equals(invitation.getStatus())) {
            throw new ApiException(ApiErrorCode.INVITATION_ALREADY_USED,
                    "This invitation was already accepted");
        }
        // Same identity rule as invite(): resending must never re-issue an
        // invitation addressed to somebody other than the slot's recipient
        // (legacy rows exist from before this rule).
        WorkflowSignerEntity slot = requireSlot(wf, invitation.getSignerOrder());
        UserEntity slotAccount = users.findByUsername(slot.getUsername())
                .orElseThrow(() -> new ApiException(
                        ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "The invited signer slot no longer has an account"));
        String slotEmail = slotAccount.getEmail() == null ? null
                : UserEntity.normalizeEmail(slotAccount.getEmail());
        if (slotEmail == null
                || !slotEmail.equals(UserEntity.normalizeEmail(invitation.getEmail()))) {
            throw new ApiException(ApiErrorCode.EMAIL_MISMATCH,
                    "This invitation does not match signer "
                            + invitation.getSignerOrder() + " any more — invite the"
                            + " address the slot is assigned to instead");
        }

        // Revoke the old token FIRST (bulk update flushed immediately, same
        // rationale as invite(): Hibernate flushes inserts before updates,
        // so a dirty-checked revoke here would collide with the partial
        // "one PENDING per slot" index when the replacement is inserted).
        invitations.revokePending(invitation.getWorkflowId(),
                invitation.getSignerOrder());

        InvitationEntity replacement = new InvitationEntity();
        replacement.setWorkflowId(invitation.getWorkflowId());
        replacement.setSignerOrder(invitation.getSignerOrder());
        replacement.setEmail(invitation.getEmail());
        replacement.setTokenHash("");
        replacement.setInvitedBy(requestedBy);
        replacement.setExpiresAt(Instant.now().plus(expiration));

        String rawToken = newToken();
        replacement.setTokenHash(hash(rawToken));
        invitations.save(replacement);

        appendAudit(wf, AuditEventType.INVITATION_REVOKED, requestedBy,
                "Previous invitation for signer " + invitation.getSignerOrder()
                        + " invalidated (resend)");
        log.info("Invitation re-issued for workflow {} signer {} (old invitation"
                        + " {} invalidated, nothing is signed by this)",
                wf.getWorkflowId(), invitation.getSignerOrder(), invitationId);
        sendEmail(replacement, wf, rawToken);
        return new ApiContracts.InvitationLinkView(
                replacement.getSignerOrder(), replacement.getEmail(),
                invitationUrl(rawToken));
    }

    /** Revokes a pending invitation. Only the workflow creator may revoke. */
    @Transactional
    public void revoke(Long invitationId, String requestedBy) {
        InvitationEntity invitation = lockedById(invitationId);
        WorkflowEntity wf = requireWorkflowEntity(invitation.getWorkflowId());
        requireCreator(wf, requestedBy);
        invitation.setStatus(InvitationEntity.STATUS_REVOKED);
        invitations.save(invitation);
        appendAudit(wf, AuditEventType.INVITATION_REVOKED, requestedBy,
                "Invitation for signer " + invitation.getSignerOrder() + " revoked");
    }

    /**
     * Public preview (brief §43): what the invited signer sees before
     * accepting. Reveals nothing but workflow name, inviter, invited email
     * and expiry.
     */
    @Transactional
    public InvitationEntity redeemableByToken(String rawToken) {
        InvitationEntity invitation = lockedByToken(rawToken);
        if (InvitationEntity.STATUS_PENDING.equals(invitation.getStatus())
                && !invitation.isRedeemable(Instant.now())) {
            invitation.setStatus(InvitationEntity.STATUS_EXPIRED);
            invitations.save(invitation);
            throw new ApiException(ApiErrorCode.INVITATION_EXPIRED,
                    "This invitation has expired — ask the workflow owner to"
                            + " send you a new invitation");
        }
        return switch (invitation.getStatus()) {
            case InvitationEntity.STATUS_ACCEPTED -> throw new ApiException(
                    ApiErrorCode.INVITATION_ALREADY_USED,
                    "This invitation has already been accepted — sign in using"
                            + " the account associated with this invitation");
            case InvitationEntity.STATUS_REVOKED -> throw new ApiException(
                    ApiErrorCode.INVITATION_REVOKED,
                    "This invitation was revoked — ask the workflow owner to"
                            + " send you a new invitation");
            case InvitationEntity.STATUS_DECLINED -> throw new ApiException(
                    ApiErrorCode.INVITATION_REVOKED,
                    "This invitation was declined");
            default -> invitation;
        };
    }

    /**
     * Accepts an invitation on behalf of an EXISTING account (brief §17):
     * the authenticated user's email must match the invited email — nobody
     * can accept into someone else's slot.
     */
    @Transactional
    public WorkflowEntity accept(String rawToken, String authenticatedUsername) {
        InvitationEntity invitation = redeemableByToken(rawToken);
        WorkflowEntity wf = requireWorkflowEntity(invitation.getWorkflowId());
        UserEntity user = users.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new ApiException(ApiErrorCode.UNAUTHORIZED,
                        "Unknown account"));
        String userEmail = user.getEmail() == null
                ? null : UserEntity.normalizeEmail(user.getEmail());
        if (userEmail == null || !userEmail.equals(invitation.getEmail())) {
            throw new ApiException(ApiErrorCode.EMAIL_MISMATCH,
                    "This invitation is bound to a different email address");
        }
        invitation.setStatus(InvitationEntity.STATUS_ACCEPTED);
        invitation.setAcceptedAt(Instant.now());
        invitation.setAcceptedBy(user.getUsername());
        invitations.save(invitation);
        appendAudit(wf, AuditEventType.INVITATION_ACCEPTED, user.getUsername(),
                "Invitation accepted by " + user.getUsername()
                        + " for signer " + invitation.getSignerOrder());
        log.info("Invitation accepted for workflow {} signer {} by {} — the signer"
                        + " stays unsigned until a signature is accepted",
                wf.getWorkflowId(), invitation.getSignerOrder(), user.getUsername());
        return wf;
    }

    /**
     * Claims an invitation as an authenticated account (brief §46): every
     * validation step is server-side — token hash, expiry, revocation,
     * workflow state, participant email — and the result is a participant-
     * scoped signing context (brief §21), never a general session. The same
     * participant may re-claim idempotently (reopen from a task list or the
     * deep-link fast path); a different account is refused (brief §19).
     */
    @Transactional
    public ApiContracts.SigningSessionView claim(
            String rawToken, String authenticatedUsername) {
        InvitationEntity invitation = lockedByToken(rawToken);
        WorkflowEntity wf = requireWorkflowEntity(invitation.getWorkflowId());
        UserEntity user = users.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new ApiException(ApiErrorCode.UNAUTHORIZED,
                        "Unknown account"));
        String userEmail = user.getEmail() == null
                ? null : UserEntity.normalizeEmail(user.getEmail());
        if (userEmail == null || !userEmail.equals(invitation.getEmail())) {
            throw new ApiException(ApiErrorCode.EMAIL_MISMATCH,
                    "This invitation was sent to a different email address."
                            + " Please sign in using the recipient account.");
        }
        boolean alreadyClaimed = false;
        if (InvitationEntity.STATUS_ACCEPTED.equals(invitation.getStatus())) {
            if (!user.getUsername().equals(invitation.getAcceptedBy())) {
                throw new ApiException(ApiErrorCode.INVITATION_ALREADY_USED,
                        "This invitation was already accepted by another account");
            }
            alreadyClaimed = true;
        } else {
            if (InvitationEntity.STATUS_PENDING.equals(invitation.getStatus())
                    && !invitation.isRedeemable(Instant.now())) {
                invitation.setStatus(InvitationEntity.STATUS_EXPIRED);
                invitations.save(invitation);
                throw new ApiException(ApiErrorCode.INVITATION_EXPIRED,
                        "This signing invitation has expired — ask the sender"
                                + " for a new invitation");
            }
            switch (invitation.getStatus()) {
                case InvitationEntity.STATUS_REVOKED -> throw new ApiException(
                        ApiErrorCode.INVITATION_REVOKED,
                        "This signing request is no longer active");
                case InvitationEntity.STATUS_DECLINED -> throw new ApiException(
                        ApiErrorCode.INVITATION_REVOKED,
                        "This signing request was declined");
                case InvitationEntity.STATUS_EXPIRED -> throw new ApiException(
                        ApiErrorCode.INVITATION_EXPIRED,
                        "This signing invitation has expired — ask the sender"
                                + " for a new invitation");
                default -> { }
            }
            invitation.setStatus(InvitationEntity.STATUS_ACCEPTED);
            invitation.setAcceptedAt(Instant.now());
            invitation.setAcceptedBy(user.getUsername());
            invitations.save(invitation);
            appendAudit(wf, AuditEventType.INVITATION_ACCEPTED, user.getUsername(),
                    "Invitation accepted by " + user.getUsername()
                            + " for signer " + invitation.getSignerOrder());
            appendAudit(wf, AuditEventType.SIGNING_SESSION_CREATED,
                    user.getUsername(),
                    "Signing session opened for signer "
                            + invitation.getSignerOrder() + " (expires "
                            + invitation.getExpiresAt() + ")");
            log.info("Invitation associated with user {} for workflow {} signer {}"
                            + " — signing session opened, nothing is signed yet",
                    user.getUsername(), wf.getWorkflowId(),
                    invitation.getSignerOrder());
        }
        return new ApiContracts.SigningSessionView(
                wf.getWorkflowId(), wf.getDocumentName(),
                invitation.getSignerOrder(), invitation.getEmail(),
                invitation.getExpiresAt() == null ? null
                        : invitation.getExpiresAt().toString(),
                alreadyClaimed);
    }

    /**
     * What a guest signing session is granted: one signer slot of one
     * workflow, through one invitation. No account, no password.
     *
     * @param principal the slot's internal signer identity key (used by the
     *                  existing slot-level authorization checks)
     */
    public record GuestSessionGrant(String principal, String workflowId,
                                    String documentName, String requestedBy,
                                    int signerOrder, String email,
                                    long invitationId, Instant invitationExpiresAt) {
    }

    /**
     * Opens a guest signing session from the invitation token alone — the
     * invited signer needs NO SignFlow account. Every check is server-side:
     * the token hash must exist, the invitation must be pending (or already
     * opened as this same slot), not expired, revoked or declined, the
     * workflow must still be active and the slot must not be signed yet.
     *
     * <p>The invitation token only AUTHORIZES access to the signing task.
     * The digital signature itself is made later with the signer's own
     * certificate on their smart card; nothing here touches it.
     *
     * <p>Re-opening the same link is allowed until the slot is signed or
     * the invitation expires (the app may be closed half-way); after a
     * signature it answers {@code INVITATION_COMPLETED}.
     */
    @Transactional
    public GuestSessionGrant openGuestSession(String rawToken) {
        InvitationEntity invitation = lockedByToken(rawToken);
        WorkflowEntity wf = requireWorkflowEntity(invitation.getWorkflowId());
        WorkflowSignerEntity slot = requireSlot(wf, invitation.getSignerOrder());
        Instant now = Instant.now();
        if (slot.isSigned()) {
            throw new ApiException(ApiErrorCode.INVITATION_COMPLETED,
                    "Your signature for this invitation is already recorded");
        }
        if (WorkflowState.CANCELLED.name().equals(wf.getStatus())
                || WorkflowState.FAILED.name().equals(wf.getStatus())) {
            throw new ApiException(ApiErrorCode.INVITATION_REVOKED,
                    "This signing request is no longer active");
        }
        if (invitation.getExpiresAt() != null && !invitation.getExpiresAt().isAfter(now)) {
            if (InvitationEntity.STATUS_PENDING.equals(invitation.getStatus())) {
                invitation.setStatus(InvitationEntity.STATUS_EXPIRED);
                invitations.save(invitation);
            }
            throw new ApiException(ApiErrorCode.INVITATION_EXPIRED,
                    "This invitation has expired — ask the requester to send a new one");
        }
        switch (invitation.getStatus()) {
            case InvitationEntity.STATUS_PENDING -> {
                invitation.setStatus(InvitationEntity.STATUS_ACCEPTED);
                invitation.setAcceptedAt(now);
                invitation.setAcceptedBy(slot.getUsername());
                invitations.save(invitation);
                appendAudit(wf, AuditEventType.INVITATION_ACCEPTED, slot.getUsername(),
                        "Invitation opened for signer " + invitation.getSignerOrder()
                                + " (guest signing session, no account)");
            }
            case InvitationEntity.STATUS_ACCEPTED -> {
                if (!slot.getUsername().equals(invitation.getAcceptedBy())) {
                    throw new ApiException(ApiErrorCode.INVITATION_ALREADY_USED,
                            "This invitation has already been used");
                }
            }
            case InvitationEntity.STATUS_EXPIRED -> throw new ApiException(
                    ApiErrorCode.INVITATION_EXPIRED,
                    "This invitation has expired — ask the requester to send a new one");
            default -> throw new ApiException(ApiErrorCode.INVITATION_REVOKED,
                    "This invitation has been revoked");
        }
        if (!isLatestForSlot(invitation)) {
            throw new ApiException(ApiErrorCode.INVITATION_REVOKED,
                    "A newer invitation replaced this one — use the latest email");
        }
        appendAudit(wf, AuditEventType.SIGNING_SESSION_CREATED, slot.getUsername(),
                "Guest signing session opened for signer " + invitation.getSignerOrder()
                        + " (invitation expires " + invitation.getExpiresAt() + ")");
        log.info("Guest signing session opened for workflow {} signer {}"
                        + " — access only, nothing is signed by this",
                wf.getWorkflowId(), invitation.getSignerOrder());
        return new GuestSessionGrant(slot.getUsername(), wf.getWorkflowId(),
                wf.getDocumentName(), wf.getCreatedBy(), invitation.getSignerOrder(),
                invitation.getEmail(), invitation.getId(), invitation.getExpiresAt());
    }

    /**
     * An invitation that was already opened (ACCEPTED) and may be opened
     * again: still the latest for its slot, not expired, slot not signed.
     * Otherwise the matching typed error.
     */
    @Transactional(readOnly = true)
    public InvitationEntity reopenableByToken(String rawToken) {
        InvitationEntity invitation = invitations.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ApiException(ApiErrorCode.INVITATION_NOT_FOUND,
                        "Invitation not found"));
        if (!InvitationEntity.STATUS_ACCEPTED.equals(invitation.getStatus())) {
            throw new ApiException(ApiErrorCode.INVITATION_ALREADY_USED,
                    "This invitation has already been used");
        }
        if (invitation.getExpiresAt() != null && !invitation.getExpiresAt().isAfter(Instant.now())) {
            throw new ApiException(ApiErrorCode.INVITATION_EXPIRED,
                    "This invitation has expired — ask the requester to send a new one");
        }
        if (!isLatestForSlot(invitation)) {
            throw new ApiException(ApiErrorCode.INVITATION_REVOKED,
                    "A newer invitation replaced this one — use the latest email");
        }
        return invitation;
    }

    /**
     * Checked on EVERY guest request: the invitation behind the session must
     * still be the active, opened invitation of that exact slot. A revoke,
     * decline, expiry or replacement by a newer invitation ends the session
     * immediately, whatever the token's own expiry says.
     */
    @Transactional(readOnly = true)
    public void requireGuestSessionActive(
            JwtService.GuestAuthentication guest) {
        InvitationEntity invitation = invitations.findById(guest.invitationId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.INVITATION_REVOKED,
                        "This signing link is no longer valid"));
        WorkflowEntity wf = requireWorkflowEntity(invitation.getWorkflowId());
        if (!wf.getWorkflowId().equals(guest.workflowId())
                || invitation.getSignerOrder() != guest.signerOrder()
                || !guest.principal().equals(slotUsername(wf, invitation.getSignerOrder()))) {
            throw new ApiException(ApiErrorCode.WORKFLOW_NOT_ACCESSIBLE,
                    "This signing link does not give access to this task");
        }
        if (!InvitationEntity.STATUS_ACCEPTED.equals(invitation.getStatus())
                || !isLatestForSlot(invitation)) {
            throw new ApiException(ApiErrorCode.INVITATION_REVOKED,
                    "This signing request is no longer active");
        }
        if (invitation.getExpiresAt() != null
                && !invitation.getExpiresAt().isAfter(Instant.now())) {
            throw new ApiException(ApiErrorCode.INVITATION_EXPIRED,
                    "This invitation has expired — ask the requester to send a new one");
        }
    }

    /** True when no newer invitation exists for the same slot. */
    private boolean isLatestForSlot(InvitationEntity invitation) {
        for (InvitationEntity other : invitations.findByWorkflowIdAndSignerOrder(
                invitation.getWorkflowId(), invitation.getSignerOrder())) {
            if (!other.getId().equals(invitation.getId())
                    && isNewer(other, invitation)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Server-side gate for signing operations (brief §48/§50/§84): once the
     * sender revokes or the recipient declines the claiming invitation, that
     * participant immediately loses signing authorization — a previously
     * claimed session must not keep working. Slots without invitation
     * history (username-based workflows) are unaffected.
     */
    @Transactional(readOnly = true)
    public void requireSigningAllowed(String workflowPublicId, String username) {
        WorkflowEntity wf = workflows.findByWorkflowId(workflowPublicId)
                .orElse(null);
        if (wf == null) {
            return;
        }
        Integer slotOrder = signers.findByWorkflowIdOrderBySignerOrder(wf.getId())
                .stream()
                .filter(s -> username.equals(s.getUsername()))
                .map(WorkflowSignerEntity::getSignerOrder)
                .findFirst().orElse(null);
        if (slotOrder == null) {
            return;
        }
        InvitationEntity latest = latestForSlot(wf.getId(), slotOrder);
        if (latest == null) {
            return;
        }
        if (InvitationEntity.STATUS_REVOKED.equals(latest.getStatus())) {
            throw new ApiException(ApiErrorCode.INVITATION_REVOKED,
                    "This signing request is no longer active — the sender"
                            + " withdrew the invitation");
        }
        if (InvitationEntity.STATUS_DECLINED.equals(latest.getStatus())) {
            throw new ApiException(ApiErrorCode.INVITATION_REVOKED,
                    "This signing request was declined — ask the sender to"
                            + " invite you again");
        }
    }

    /**
     * Declines an invitation (the slot stays unsigned; the owner can
     * resend). The authenticated user's email must match the invited email.
     */
    @Transactional
    public void decline(String rawToken, String authenticatedUsername) {
        InvitationEntity invitation = redeemableByToken(rawToken);
        WorkflowEntity wf = requireWorkflowEntity(invitation.getWorkflowId());
        UserEntity user = users.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new ApiException(ApiErrorCode.UNAUTHORIZED,
                        "Unknown account"));
        String userEmail = user.getEmail() == null
                ? null : UserEntity.normalizeEmail(user.getEmail());
        if (userEmail == null || !userEmail.equals(invitation.getEmail())) {
            throw new ApiException(ApiErrorCode.EMAIL_MISMATCH,
                    "This invitation is bound to a different email address");
        }
        invitation.setStatus(InvitationEntity.STATUS_DECLINED);
        invitations.save(invitation);
        appendAudit(wf, AuditEventType.INVITATION_REVOKED, user.getUsername(),
                "Invitation declined for signer " + invitation.getSignerOrder());
    }

    /**
     * Sends the invitation email and records the delivery outcome on the
     * invitation row (brief §37): SENT only when the transport accepted the
     * message, FAILED (plus an audit event and a friendly owner-facing
     * error) when it did not.
     *
     * <p>On failure this throws {@link EmailDeliveryException}, which
     * {@link #invite} and {@link #resend} exclude from rollback: the row
     * keeps its FAILED marker and the owner can hit Resend instead of
     * recreating the invitation.
     */
    private void sendEmail(InvitationEntity invitation, WorkflowEntity wf,
                           String rawToken) {
        String url = invitationUrl(rawToken);
        try {
            emailService.sendSigningInvitation(invitation.getEmail(),
                    signerName(invitation, wf), wf.getDocumentName(),
                    wf.getCreatedBy(), url, invitation.getExpiresAt());
        } catch (RuntimeException e) {
            invitation.setEmailStatus(EmailStatus.FAILED.name());
            invitations.save(invitation);
            appendAudit(wf, AuditEventType.EMAIL_DELIVERY_FAILED, wf.getCreatedBy(),
                    "Invitation email for signer " + invitation.getSignerOrder()
                            + " could not be delivered: " + e.getMessage());
            log.warn("Invitation email delivery FAILED for workflow {} signer {}",
                    wf.getWorkflowId(), invitation.getSignerOrder(), e);
            // Its own type on purpose: the caller's transaction commits the
            // row (with the FAILED marker above) instead of undoing it.
            throw new EmailDeliveryException(
                    "The invitation was created, but the email could not be"
                            + " delivered. Use Resend to try again.");
        }
        invitation.setEmailStatus(EmailStatus.SENT.name());
        invitations.save(invitation);
        appendAudit(wf, AuditEventType.INVITATION_SENT, wf.getCreatedBy(),
                "Invitation sent for signer " + invitation.getSignerOrder()
                        + " (expires " + invitation.getExpiresAt() + ")");
        log.info("Invitation for workflow {} signer {} — token hash stored,"
                        + " email dispatched to {}", wf.getWorkflowId(),
                invitation.getSignerOrder(), invitation.getEmail());
    }

    /**
     * Best-effort human name for the greeting: the invited account's display
     * name when the slot already has one, otherwise the email local part.
     */
    private String signerName(InvitationEntity invitation, WorkflowEntity wf) {
        return users.findByUsername(slotUsername(wf, invitation.getSignerOrder()))
                .map(UserEntity::getDisplayName)
                .filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> {
                    String email = invitation.getEmail();
                    int at = email.indexOf('@');
                    return at > 0 ? email.substring(0, at) : email;
                });
    }

    /** Username occupying a signer slot, or null when the slot has none yet. */
    public String slotUsername(WorkflowEntity wf, int signerOrder) {
        return signers.findByWorkflowIdOrderBySignerOrder(wf.getId()).stream()
                .filter(s -> s.getSignerOrder() == signerOrder)
                .map(WorkflowSignerEntity::getUsername)
                .findFirst()
                .orElse(null);
    }

    /** 256-bit SecureRandom token, URL-safe. */
    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of the raw token — the only value ever persisted. */
    private static String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private WorkflowEntity requireWorkflow(String workflowPublicId) {
        return workflows.findByWorkflowId(workflowPublicId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.WORKFLOW_NOT_FOUND,
                        "Workflow not found"));
    }

    private WorkflowEntity requireWorkflowEntity(Long id) {
        return workflows.findById(id)
                .orElseThrow(() -> new ApiException(ApiErrorCode.WORKFLOW_NOT_FOUND,
                        "Workflow not found"));
    }

    private static void requireCreator(WorkflowEntity wf, String requestedBy) {
        if (!wf.getCreatedBy().equals(requestedBy)) {
            throw new ApiException(ApiErrorCode.UNAUTHORIZED,
                    "Only the workflow owner can manage invitations");
        }
    }

    /**
     * Appends an audit event through the single {@link AuditLog} writer,
     * which takes the workflow row lock and assigns the sequence number in
     * the database (no in-memory sequence arithmetic, no lost events).
     */
    private void appendAudit(WorkflowEntity wf, AuditEventType type, String actor,
                             String detail) {
        auditLog.append(wf.getId(), type, actor, detail);
    }
}

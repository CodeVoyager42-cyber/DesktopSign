package com.example.signer.server.invites;

import com.example.signer.server.db.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.signer.server.AuditEventType;
import com.example.signer.server.api.ApiErrorType.ApiErrorCode;
import com.example.signer.server.api.ApiErrorType.ApiException;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.entity.WorkflowEntity;
import com.example.signer.server.db.entity.WorkflowSignerEntity;
import com.example.signer.server.db.repository.AuditEventRepository;
import com.example.signer.server.db.entity.AuditEventEntity;
import com.example.signer.server.db.repository.InvitationRepository;
import com.example.signer.server.db.repository.UserRepository;
import com.example.signer.server.db.repository.WorkflowRepository;
import com.example.signer.server.db.repository.WorkflowSignerRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Invitation-bound registration (brief §16): the invited EMAIL comes from
 * the invitation record — never from the client. The account row was
 * pre-created as a {@code PENDING_INVITE} placeholder when the workflow
 * owner invited the email; registration activates it inside one transaction
 * (validate invitation → set password/display name → mark accepted → audit),
 * so account creation and invitation acceptance cannot become inconsistent.
 *
 * <p>If the email already has an ACTIVE account there is nothing to
 * register: the caller is told to sign in (brief §17 — no duplicate users).
 */
@Service
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class RegistrationService {

    private static final Logger log =
            LoggerFactory.getLogger(RegistrationService.class);

    private final InvitationService invitations;
    private final InvitationRepository invitationRepository;
    private final UserRepository users;
    private final WorkflowSignerRepository signers;
    private final WorkflowRepository workflows;
    private final AuditEventRepository auditEvents;
    private final AuditLog auditLog;

    public RegistrationService(InvitationService invitations,
                               InvitationRepository invitationRepository,
                               UserRepository users,
                               WorkflowSignerRepository signers,
                               WorkflowRepository workflows,
                               AuditEventRepository auditEvents,
                               AuditLog auditLog) {
        this.invitations = invitations;
        this.invitationRepository = invitationRepository;
        this.users = users;
        this.signers = signers;
        this.workflows = workflows;
        this.auditEvents = auditEvents;
        this.auditLog = auditLog;
    }

    /**
     * Registers the account for the invitation's signer slot.
     *
     * @return the username of the activated account (login eligible
     *         immediately; the caller issues the token pair)
     */
    @Transactional
    public String register(String invitationToken, String displayName,
                           String password) {
        if (invitationToken == null || invitationToken.isBlank()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "The invitation token is required");
        }
        validatePassword(password);

        InvitationEntity invitation =
                invitations.redeemableByToken(invitationToken.trim());

        String slotUsername = signers
                .findByWorkflowIdOrderBySignerOrder(invitation.getWorkflowId())
                .stream()
                .filter(s -> s.getSignerOrder() == invitation.getSignerOrder())
                .findFirst()
                .map(WorkflowSignerEntity::getUsername)
                .orElseThrow(() -> new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "The invited signer slot no longer exists"));

        UserEntity account = users.findByUsername(slotUsername)
                .orElseThrow(() -> new ApiException(ApiErrorCode.INVALID_WORKFLOW_STATE,
                        "The invited account no longer exists"));
        // The registration identity must be the address the invitation was
        // addressed to — the email comes from the invitation record, never
        // from the client, and the slot must reference that same address.
        String slotEmail = account.getEmail() == null ? null
                : UserEntity.normalizeEmail(account.getEmail());
        if (slotEmail == null
                || !slotEmail.equals(UserEntity.normalizeEmail(invitation.getEmail()))) {
            throw new ApiException(ApiErrorCode.EMAIL_MISMATCH,
                    "This invitation is not bound to the invited signer slot");
        }
        if (account.isActive()) {
            throw new ApiException(ApiErrorCode.INVITATION_ALREADY_USED,
                    "This email already has an account — sign in instead of"
                            + " registering");
        }

        account.setDisplayName(displayName == null || displayName.isBlank()
                ? account.getDisplayName() : displayName.trim());
        account.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt()));
        account.setStatus("ACTIVE");
        users.save(account);

        invitation.setStatus(InvitationEntity.STATUS_ACCEPTED);
        invitation.setAcceptedAt(Instant.now());
        invitation.setAcceptedBy(account.getUsername());
        invitationRepository.save(invitation);

        // Public workflow id (WF-…) for the log line; the audit block reuses
        // the same entity so nothing is loaded twice.
        WorkflowEntity workflow = workflows.findById(invitation.getWorkflowId())
                .orElse(null);
        String publicWorkflowId = workflow == null
                ? String.valueOf(invitation.getWorkflowId())
                : workflow.getWorkflowId();

        if (workflow != null) {
            // Single audit writer: row lock + database-assigned sequence.
            auditLog.append(workflow.getId(), AuditEventType.SIGNER_REGISTERED,
                    account.getUsername(),
                    "Account registered through invitation for signer "
                            + invitation.getSignerOrder());
            // Registration claims the invitation and opens the participant's
            // signing session (brief §21/§46) — same audit as POST /claim.
            auditLog.append(workflow.getId(), AuditEventType.SIGNING_SESSION_CREATED,
                    account.getUsername(),
                    "Signing session opened for signer " + invitation.getSignerOrder());
        }

        log.info("Recipient registered through invitation: workflow {} signer {}"
                        + " (user {}) — the signer remains UNSIGNED until a"
                        + " signature submission is accepted",
                publicWorkflowId, invitation.getSignerOrder(),
                account.getUsername());
        return account.getUsername();
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 200) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST,
                    "The password must be between 8 and 200 characters");
        }
    }
}

package com.example.signer.server.invites;

import com.example.signer.server.db.AuditLog;
import com.example.signer.server.workflow.AuditEventType;
import com.example.signer.server.db.entity.AuditEventEntity;
import com.example.signer.server.db.entity.InvitationEntity;
import com.example.signer.server.db.repository.AuditEventRepository;
import com.example.signer.server.db.repository.InvitationRepository;
import com.example.signer.server.db.repository.WorkflowRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Active invitation-expiry sweep (brief §41): pending invitations whose TTL
 * has passed are flipped to EXPIRED with an audit event, instead of only
 * being invalidated lazily when someone presents the token. Runs every 15
 * minutes in database mode; scheduling is enabled by
 * {@code WorkflowServerApplication}.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class InvitationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(
            InvitationExpirySweeper.class);

    private final InvitationRepository invitations;
    private final WorkflowRepository workflows;
    private final AuditEventRepository auditEvents;
    private final AuditLog auditLog;

    public InvitationExpirySweeper(InvitationRepository invitations,
                                   WorkflowRepository workflows,
                                   AuditEventRepository auditEvents,
                               AuditLog auditLog) {
        this.invitations = invitations;
        this.workflows = workflows;
        this.auditEvents = auditEvents;
        this.auditLog = auditLog;
    }

    /** Expires every overdue PENDING invitation; runs every 15 minutes. */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    @Transactional
    public void expireOverdueInvitations() {
        List<InvitationEntity> overdue = invitations
                .findByStatusAndExpiresAtBefore(InvitationEntity.STATUS_PENDING,
                        Instant.now());
        for (InvitationEntity invitation : overdue) {
            invitation.setStatus(InvitationEntity.STATUS_EXPIRED);
            invitations.save(invitation);
            // The slot number, not the address: the trail is readable by every
            // participant and a slot's address is owner-only (§6 of
            // docs/security/phase-e-workflows.md).
            auditLog.append(invitation.getWorkflowId(), AuditEventType.INVITATION_EXPIRED,
                    "system", "Invitation for signer " + invitation.getSignerOrder()
                            + " expired");
        }
        if (!overdue.isEmpty()) {
            log.info("Invitation sweep: expired {} overdue invitation(s)",
                    overdue.size());
        }
    }
}

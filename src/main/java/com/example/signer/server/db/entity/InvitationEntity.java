package com.example.signer.server.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One email invitation to sign a workflow (brief §13). The database stores
 * ONLY the SHA-256 hash of the invitation token ({@code tokenHash}) — the
 * raw token is sent to the signer once and can never be recovered from a
 * storage dump. Uniqueness: one live invitation per (workflow, signer slot).
 */
@Entity
@Table(name = "invitations", uniqueConstraints = {
        @UniqueConstraint(name = "uq_invitations_token", columnNames = "token_hash"),
        @UniqueConstraint(name = "uq_invitations_workflow_signer",
                columnNames = {"workflow_id", "signer_order"})})
public class InvitationEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_DECLINED = "DECLINED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "signer_order", nullable = false)
    private int signerOrder;

    /** The invited email (normalized lower-case) — bound at acceptance. */
    @Column(nullable = false, length = 255)
    private String email;

    /** SHA-256 hex of the raw invitation token. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** PENDING | ACCEPTED | EXPIRED | REVOKED | DECLINED. */
    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    /**
     * Email delivery outcome, tracked separately from the invitation status
     * (brief §37): PENDING = not yet attempted, SENT = handed to the mail
     * transport without error, FAILED = the transport reported failure.
     * Null for rows created before this column existed (= unknown).
     */
    @Column(name = "email_status", length = 20)
    private String emailStatus;

    @Column(name = "invited_by", nullable = false, length = 100)
    private String invitedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by", length = 100)
    private String acceptedBy;

    /** SHA-256 of the current guest verification code (V7); never the code. */
    @Column(name = "guest_code_hash", length = 64)
    private String guestCodeHash;

    @Column(name = "guest_code_expires_at")
    private Instant guestCodeExpiresAt;

    @Column(name = "guest_code_sent_at")
    private Instant guestCodeSentAt;

    /** Wrong entries for the current code. */
    @Column(name = "guest_code_attempts", nullable = false)
    private int guestCodeAttempts;

    /** Wrong entries in total for this invitation. */
    @Column(name = "guest_code_failures", nullable = false)
    private int guestCodeFailures;

    public InvitationEntity() {
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public int getSignerOrder() {
        return signerOrder;
    }

    public void setSignerOrder(int signerOrder) {
        this.signerOrder = signerOrder;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = UserEntity.normalizeEmail(email);
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEmailStatus() {
        return emailStatus;
    }

    public void setEmailStatus(String emailStatus) {
        this.emailStatus = emailStatus;
    }

    public String getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(String invitedBy) {
        this.invitedBy = invitedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public String getAcceptedBy() {
        return acceptedBy;
    }

    public void setAcceptedBy(String acceptedBy) {
        this.acceptedBy = acceptedBy;
    }

    /** Whether the invitation is still redeemable. */
    public boolean isRedeemable(Instant now) {
        return STATUS_PENDING.equals(status) && now.isBefore(expiresAt);
    }

    public String getGuestCodeHash() {
        return guestCodeHash;
    }

    public Instant getGuestCodeExpiresAt() {
        return guestCodeExpiresAt;
    }

    public Instant getGuestCodeSentAt() {
        return guestCodeSentAt;
    }

    public int getGuestCodeAttempts() {
        return guestCodeAttempts;
    }

    public int getGuestCodeFailures() {
        return guestCodeFailures;
    }

    /** Stores a newly sent code (its hash) and resets the per-code attempts. */
    public void startGuestCode(String codeHash, Instant sentAt, Instant expiresAt) {
        this.guestCodeHash = codeHash;
        this.guestCodeSentAt = sentAt;
        this.guestCodeExpiresAt = expiresAt;
        this.guestCodeAttempts = 0;
    }

    /** Counts a wrong entry; the code dies after {@code maxAttempts}. */
    public void recordWrongGuestCode(int maxAttempts) {
        this.guestCodeAttempts++;
        this.guestCodeFailures++;
        if (this.guestCodeAttempts >= maxAttempts) {
            this.guestCodeHash = null;
            this.guestCodeExpiresAt = null;
        }
    }

    /** A code is single use: forget it once it opened a session. */
    public void consumeGuestCode() {
        this.guestCodeHash = null;
        this.guestCodeExpiresAt = null;
        this.guestCodeAttempts = 0;
    }
}

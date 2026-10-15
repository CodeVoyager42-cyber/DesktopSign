package com.example.signer.server.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One signer slot of a workflow (brief §21) — the relational counterpart of
 * {@code WorkflowSignerRecord}. Unique per (workflow, order) and per
 * (workflow, user); the schema carries real foreign keys to {@code users}.
 *
 * <p>The {@code signature_*} columns are reserved for per-signer placement
 * persistence in PDF coordinates (Phase 8); they are {@code null} until the
 * signing submission carries placement metadata.
 */
@Entity
@Table(name = "workflow_signers", uniqueConstraints = {
        @UniqueConstraint(name = "uq_signers_workflow_order",
                columnNames = {"workflow_id", "signer_order"}),
        @UniqueConstraint(name = "uq_signers_workflow_user",
                columnNames = {"workflow_id", "username"})})
public class WorkflowSignerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "signer_order", nullable = false)
    private int signerOrder;

    @Column(nullable = false, length = 100)
    private String username;

    /** SHA-256 fingerprint of the expected signer certificate; null = UNBOUND. */
    @Column(name = "certificate_sha256", length = 64)
    private String certificateSha256;

    @Column(name = "bound_at")
    private Instant boundAt;

    @Column(nullable = false)
    private boolean signed;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signing_operation_id", length = 64)
    private String signingOperationId;

    @Column(name = "invited_at")
    private Instant invitedAt;

    // ── reserved: signature placement in PDF coordinates (Phase 8) ──────
    @Column(name = "signature_page")
    private Integer signaturePage;

    @Column(name = "signature_x", precision = 12, scale = 3)
    private BigDecimal signatureX;

    @Column(name = "signature_y", precision = 12, scale = 3)
    private BigDecimal signatureY;

    @Column(name = "signature_width", precision = 12, scale = 3)
    private BigDecimal signatureWidth;

    @Column(name = "signature_height", precision = 12, scale = 3)
    private BigDecimal signatureHeight;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public WorkflowSignerEntity() {
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getCertificateSha256() {
        return certificateSha256;
    }

    public void setCertificateSha256(String certificateSha256) {
        this.certificateSha256 = certificateSha256;
    }

    public Instant getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(Instant boundAt) {
        this.boundAt = boundAt;
    }

    public boolean isSigned() {
        return signed;
    }

    public void setSigned(boolean signed) {
        this.signed = signed;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(Instant signedAt) {
        this.signedAt = signedAt;
    }

    public String getSigningOperationId() {
        return signingOperationId;
    }

    public void setSigningOperationId(String signingOperationId) {
        this.signingOperationId = signingOperationId;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public void setInvitedAt(Instant invitedAt) {
        this.invitedAt = invitedAt;
    }

    public Integer getSignaturePage() {
        return signaturePage;
    }

    public void setSignaturePage(Integer signaturePage) {
        this.signaturePage = signaturePage;
    }

    public BigDecimal getSignatureX() {
        return signatureX;
    }

    public void setSignatureX(BigDecimal signatureX) {
        this.signatureX = signatureX;
    }

    public BigDecimal getSignatureY() {
        return signatureY;
    }

    public void setSignatureY(BigDecimal signatureY) {
        this.signatureY = signatureY;
    }

    public BigDecimal getSignatureWidth() {
        return signatureWidth;
    }

    public void setSignatureWidth(BigDecimal signatureWidth) {
        this.signatureWidth = signatureWidth;
    }

    public BigDecimal getSignatureHeight() {
        return signatureHeight;
    }

    public void setSignatureHeight(BigDecimal signatureHeight) {
        this.signatureHeight = signatureHeight;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

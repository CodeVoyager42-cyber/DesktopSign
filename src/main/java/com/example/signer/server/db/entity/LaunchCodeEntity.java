package com.example.signer.server.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One launch code (V8). Holds only the SHA-256 of the code, never the code. */
@Entity
@Table(name = "launch_codes")
public class LaunchCodeEntity {

    /** The only purpose so far: open one signing task in the desktop app. */
    public static final String PURPOSE_SIGN = "SIGN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "signer_order", nullable = false)
    private int signerOrder;

    @Column(nullable = false, length = 20)
    private String purpose;

    @Column(name = "min_app_version", nullable = false, length = 20)
    private String minAppVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "consumed_app_version", length = 20)
    private String consumedAppVersion;

    @Column(name = "consumed_device", length = 64)
    private String consumedDevice;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected LaunchCodeEntity() {
    }

    public LaunchCodeEntity(String codeHash, String username, Long workflowId, int signerOrder,
                            String purpose, String minAppVersion, Instant createdAt,
                            Instant expiresAt) {
        this.codeHash = codeHash;
        this.username = username;
        this.workflowId = workflowId;
        this.signerOrder = signerOrder;
        this.purpose = purpose;
        this.minAppVersion = minAppVersion;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public String getUsername() {
        return username;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public int getSignerOrder() {
        return signerOrder;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getMinAppVersion() {
        return minAppVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public String getConsumedAppVersion() {
        return consumedAppVersion;
    }

    public String getConsumedDevice() {
        return consumedDevice;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }
}

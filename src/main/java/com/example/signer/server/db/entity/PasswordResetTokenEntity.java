package com.example.signer.server.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One password-reset token (V10). Holds only the SHA-256 hash of the token
 * value; the value itself is e-mailed once and never stored or logged.
 *
 * <p>Deliberately short-lived (30 minutes) and single use: consuming it sets
 * a new password and revokes every session the account has, so it is the most
 * powerful link the system ever sends.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected PasswordResetTokenEntity() {
    }

    public PasswordResetTokenEntity(String tokenHash, String username,
                                    Instant createdAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.username = username;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getUsername() {
        return username;
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

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    /** Usable = not yet consumed and not yet expired. */
    public boolean isUsable(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }
}

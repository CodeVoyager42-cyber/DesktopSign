package com.example.signer.server.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One e-mail verification token (V10). Holds only the SHA-256 hash of the
 * token value; the value itself is e-mailed once and never stored or logged.
 *
 * <p>{@code email} is the address the token was sent to. It is kept
 * separately from the account because the account's address can change
 * between issuing and use: the token proves the address it was delivered to,
 * never whatever the account happens to hold later.
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected EmailVerificationTokenEntity() {
    }

    public EmailVerificationTokenEntity(String tokenHash, String username, String email,
                                        Instant createdAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.username = username;
        this.email = email;
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

    public String getEmail() {
        return email;
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

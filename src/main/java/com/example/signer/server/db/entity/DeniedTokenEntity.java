package com.example.signer.server.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One access token that has been signed out of (V12, security Phase 2 risk
 * L4): its {@code jti} and the {@code exp} of the token it came from.
 *
 * <p>Unlike {@link RefreshTokenEntity} this holds the identifier itself and
 * not a hash of it — the reasoning is in the migration. There is no column
 * for the account: a row says that one token is dead, not who ended it.
 */
@Entity
@Table(name = "denied_access_tokens")
public class DeniedTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_id", nullable = false, unique = true, length = 64)
    private String tokenId;

    @Column(name = "denied_at", nullable = false)
    private Instant deniedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected DeniedTokenEntity() {
    }

    public DeniedTokenEntity(String tokenId, Instant deniedAt, Instant expiresAt) {
        this.tokenId = tokenId;
        this.deniedAt = deniedAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getTokenId() {
        return tokenId;
    }

    public Instant getDeniedAt() {
        return deniedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}

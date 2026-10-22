package com.example.signer.server.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Failed sign-ins for one account from one client address (V13, security
 * Phase 2 risk L3). The row the sign-in path reads to decide whether this
 * address is locked out of this account.
 *
 * <p>Rows are written by upsert and read by the pair, never by the address
 * alone — nothing in the application asks what one address has been
 * attacking.
 */
@Entity
@Table(name = "login_failures")
public class LoginFailureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "client_address", nullable = false, length = 64)
    private String clientAddress;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "first_failure_at", nullable = false)
    private Instant firstFailureAt;

    @Column(name = "last_failure_at", nullable = false)
    private Instant lastFailureAt;

    protected LoginFailureEntity() {
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getFirstFailureAt() {
        return firstFailureAt;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }
}

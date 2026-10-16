package com.example.signer.server.db.entity;

import java.util.Locale;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent account (brief §8). {@code username} is the workflow identity
 * the existing API uses; {@code email} is the invitation identity. Both are
 * unique at the database level; since V5 usernames are unique ignoring
 * letter case. {@code passwordHash} is bcrypt ({@code $2a$...}); it is {@code null} only while the row exists as an
 * invited placeholder ({@code status = PENDING_INVITE}) that cannot log in.
 *
 * <p>Never log the password hash, invitations tokens, or any token value.
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(unique = true)
    private String email;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    /** Account-wide role (V5). Defaults to USER; ADMIN is never implied. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountRole role = AccountRole.USER;

    /** When the account proved it controls {@link #email} (V5); null = not proven. */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** When the password was last set (V5); null for accounts without one. */
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /** Consecutive failed sign-ins (V5). Stored now, enforced from Phase 2. */
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    /** Sign-in blocked until this instant (V5). Stored now, enforced from Phase 2. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public UserEntity() {
    }

    public UserEntity(String username, String email, String passwordHash,
                      String displayName, AccountStatus status) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = normalizeEmail(email);
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public AccountRole getRole() {
        return role;
    }

    public void setRole(AccountRole role) {
        this.role = role;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public void setFailedLoginCount(int failedLoginCount) {
        this.failedLoginCount = failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    /**
     * Stores a new password hash and records when it changed. Use this rather
     * than {@link #setPasswordHash} whenever a user sets or rehashes a password.
     */
    public void changePasswordHash(String passwordHash, Instant when) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = when;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    /** Whether this row can authenticate (active account with a password). */
    public boolean isActive() {
        return status == AccountStatus.ACTIVE && passwordHash != null;
    }

    /**
     * Normalizes an email for uniqueness (brief §8): trim + lower-case the
     * domain part; the local part keeps its case but is compared
     * case-insensitively by storing lower-case for known providers. The
     * simple, deterministic rule here: trim + full lower-case, so
     * {@code Bob@Example.com} collides with {@code bob@example.com}.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}

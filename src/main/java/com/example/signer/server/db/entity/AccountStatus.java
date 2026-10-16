package com.example.signer.server.db.entity;

/**
 * Lifecycle of a {@code users} row. Stored as its name (the values the V1
 * CHECK constraint already allows), so existing rows map unchanged.
 */
public enum AccountStatus {
    /** Registered account with a password; may sign in. */
    ACTIVE,
    /** Switched off by an operator; may not sign in. */
    DISABLED,
    /** Invitation identity only: no password, may not sign in. */
    PENDING_INVITE
}

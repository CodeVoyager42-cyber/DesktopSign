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
    /**
     * A placeholder row: no password, may not sign in. Created by the
     * invitation workflow and by the file-to-database import; nothing creates
     * one from an invitation any more, and the name is kept because the value
     * is stored in the users table.
     */
    PENDING_INVITE
}

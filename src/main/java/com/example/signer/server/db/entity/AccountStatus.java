package com.example.signer.server.db.entity;

/**
 * Lifecycle of a {@code users} row. Stored as its name; V14 is the migration
 * that fixed the allowed set to these three.
 */
public enum AccountStatus {
    /** Registered account with a password; may sign in. */
    ACTIVE,
    /** Switched off by an operator; may not sign in. */
    DISABLED,
    /**
     * A name a workflow refers to, with no password and no way to sign in.
     * Only the file→DB importer creates these, for a username an imported
     * workflow references that has no account of its own.
     */
    PLACEHOLDER
}

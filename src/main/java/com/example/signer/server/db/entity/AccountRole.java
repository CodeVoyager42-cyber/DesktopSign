package com.example.signer.server.db.entity;

/**
 * Account-wide permission level (V5 {@code users.role}). Workflow rights —
 * owner, signer — come from the workflow itself, never from this role.
 * {@link #ADMIN} is never granted automatically; no code path assigns it yet.
 */
public enum AccountRole {
    USER,
    ADMIN
}

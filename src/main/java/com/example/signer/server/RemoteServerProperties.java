package com.example.signer.server;

import com.example.signer.server.auth.DevPasswordHasher;
import com.example.signer.server.workflow.WorkflowSignerRecord;
import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.signer.server.auth.PasswordHashing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration of the coordination server ({@code signature.remote-server.*},
 * active only in the {@code server} profile).
 *
 * <p>Password handling: values under {@code users} are stored as bcrypt
 * hashes ({@code $2a$...}). Development-friendly behaviour: a value that is
 * not a bcrypt hash is treated as a plaintext development password and
 * transparently upgraded at startup by {@link DevPasswordHasher} (dev mode
 * only — see {@code signature.remote-server.allow-plaintext-passwords}).
 * Plaintext passwords therefore never remain in memory and never appear in
 * logs. {@link #matches} performs bcrypt (constant-time) verification.
 */
@ConfigurationProperties(prefix = "signature.remote-server")
public class RemoteServerProperties {

    /** Filesystem directory for workflow state and revisions. */
    private String storage;

    /**
     * User id → bcrypt password hash at runtime. Empty by default: there are
     * NO built-in accounts. The development users (alice, bob, charlie) are
     * configured only by the {@code dev} profile. The user id doubles as the
     * workflow signer identity ({@code WorkflowSignerRecord.userId}).
     */
    private Map<String, String> users = new LinkedHashMap<>();

    /**
     * Dev escape hatch, OFF by default: when false (production posture),
     * starting the server with a plaintext password in {@code users} fails
     * fast instead of being auto-hashed, and no development accounts are
     * seeded. Only the {@code dev} profile turns it on.
     */
    private boolean allowPlaintextPasswords = false;

    /**
     * OFF by default (production posture): the server refuses certificates
     * and signatures produced by the development mock token (marker OU or one
     * of the legacy shared fingerprints). Only the {@code dev} profile turns
     * it on so mock-token workflows keep working in development.
     */
    private boolean acceptDevelopmentIdentities = false;

    public boolean isAcceptDevelopmentIdentities() {
        return acceptDevelopmentIdentities;
    }

    public void setAcceptDevelopmentIdentities(boolean acceptDevelopmentIdentities) {
        this.acceptDevelopmentIdentities = acceptDevelopmentIdentities;
    }

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public Map<String, String> getUsers() {
        return users;
    }

    public void setUsers(Map<String, String> users) {
        this.users = users;
    }

    public boolean isAllowPlaintextPasswords() {
        return allowPlaintextPasswords;
    }

    public void setAllowPlaintextPasswords(boolean allowPlaintextPasswords) {
        this.allowPlaintextPasswords = allowPlaintextPasswords;
    }

    /** Bcrypt (constant-time) password check for {@code userId}. */
    public boolean matches(String userId, String password) {
        String expected = users.get(userId);
        if (expected == null || password == null) {
            return false;
        }
        return PasswordHashing.matches(password, expected);
    }

    /** Whether {@code userId} is a known workflow user. */
    public boolean knowsUser(String userId) {
        return userId != null && users.containsKey(userId);
    }
}

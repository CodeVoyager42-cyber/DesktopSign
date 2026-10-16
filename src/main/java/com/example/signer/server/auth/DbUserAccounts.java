package com.example.signer.server.auth;

import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Accounts from the {@code users} table (brief Phase 2/§8-§9): bcrypt
 * verification, status enforcement (only ACTIVE rows may log in; invited
 * placeholders have no password), login by username OR normalized email.
 * Unknown user and wrong password are indistinguishable to the caller.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class DbUserAccounts implements UserAccountService {

    private final UserRepository users;

    public DbUserAccounts(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean matches(String usernameOrEmail, String password) {
        if (usernameOrEmail == null || usernameOrEmail.isBlank() || password == null) {
            return false;
        }
        Optional<UserEntity> account = usernameOrEmail.contains("@")
                ? users.findByEmail(UserEntity.normalizeEmail(usernameOrEmail))
                : users.findByUsername(usernameOrEmail.trim());
        UserEntity user = account.orElse(null);
        if (user == null || !user.isActive()) {
            // Same answer as a wrong password, after the same bcrypt work, so
            // neither the answer nor the response time reveals whether the
            // account exists or can sign in.
            PasswordHashing.simulateCheck(password);
            return false;
        }
        boolean ok = PasswordHashing.matches(password, user.getPasswordHash());
        if (ok && PasswordHashing.needsRehash(user.getPasswordHash())) {
            rehash(user, password);
        }
        return ok;
    }

    /**
     * Replaces a hash made with an older, cheaper cost by a cost-12 one,
     * now that the plain password is known to be right. A failure here never
     * fails the login; the old hash simply stays.
     */
    private void rehash(UserEntity user, String password) {
        try {
            user.setPasswordHash(PasswordHashing.hash(password));
            users.save(user);
        } catch (RuntimeException e) {
            // Deliberately not logged with the exception message: it could
            // describe the password. The old hash keeps working.
        }
    }

    @Override
    public boolean knowsUser(String username) {
        return username != null && users.existsByUsername(username.trim());
    }

    /**
     * Registered = ACTIVE with a password. A {@code PENDING_INVITE}
     * placeholder is deliberately NOT registered: the invited person exists
     * only as an invitation identity until they complete registration.
     */
    @Override
    public boolean isRegistered(String username) {
        if (username == null) {
            return false;
        }
        return users.findByUsername(username.trim())
                .map(UserEntity::isActive)
                .orElse(false);
    }

    /**
     * Canonical account id for a login identifier: an email resolves to the
     * account that owns it, a username to itself. Called only after
     * {@link #matches} succeeded, so the identifier is known to exist.
     */
    @Override
    public String accountId(String usernameOrEmail) {
        if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
            return null;
        }
        String identifier = usernameOrEmail.trim();
        if (identifier.contains("@")) {
            return users.findByEmail(UserEntity.normalizeEmail(identifier))
                    .map(UserEntity::getUsername)
                    .orElse(identifier);
        }
        return users.findByUsername(identifier)
                .map(UserEntity::getUsername)
                .orElse(identifier);
    }

    @Override
    @Transactional
    public void onLoginSuccess(String username) {
        users.findByUsername(username.trim()).ifPresent(user ->
                user.setLastLoginAt(Instant.now()));
    }
}

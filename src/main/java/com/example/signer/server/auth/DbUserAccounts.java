package com.example.signer.server.auth;

import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Accounts from the {@code users} table (brief Phase 2/§8-§9): bcrypt
 * verification, status enforcement (only ACTIVE rows may log in; invited
 * placeholders have no password), login by username OR normalized email.
 * Unknown user and wrong password are indistinguishable to the caller.
 *
 * <p>Lockout (security Phase 2): after {@code max-failures} wrong passwords
 * in a row (default 5) the account refuses every password, including the
 * right one, for {@code lock-minutes} (default 15). A locked account gets
 * the same answer, after the same bcrypt work, as a wrong password, so the
 * lock does not reveal that the account exists. A successful sign-in clears
 * the count.
 */
@Component
@ConditionalOnProperty(name = "signature.remote-server.database.enabled",
        havingValue = "true")
public class DbUserAccounts implements UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(DbUserAccounts.class);

    public static final int DEFAULT_MAX_FAILURES = 5;
    public static final Duration DEFAULT_LOCK = Duration.ofMinutes(15);

    /** How long an unverified, unused account is kept (risk D11). */
    public static final Duration DEFAULT_UNVERIFIED_RETENTION = Duration.ofDays(7);

    private final UserRepository users;
    private final int maxFailures;
    private final Duration lockDuration;
    private final Duration unverifiedRetention;

    /** Default policy: 5 failures, 15 minutes. */
    public DbUserAccounts(UserRepository users) {
        this(users, DEFAULT_MAX_FAILURES, DEFAULT_LOCK.toMinutes(),
                DEFAULT_UNVERIFIED_RETENTION);
    }

    /** Chosen lockout policy, with the default unverified-account retention. */
    public DbUserAccounts(UserRepository users, int maxFailures, long lockMinutes) {
        this(users, maxFailures, lockMinutes, DEFAULT_UNVERIFIED_RETENTION);
    }

    @Autowired
    public DbUserAccounts(UserRepository users,
            @Value("${signature.remote-server.login.max-failures:5}") int maxFailures,
            @Value("${signature.remote-server.login.lock-minutes:15}") long lockMinutes,
            @Value("${signature.remote-server.web.unverified-retention:P7D}")
                    Duration unverifiedRetention) {
        if (maxFailures < 1 || lockMinutes < 1) {
            throw new IllegalStateException(
                    "signature.remote-server.login.max-failures and lock-minutes must be >= 1");
        }
        this.users = users;
        this.maxFailures = maxFailures;
        this.lockDuration = Duration.ofMinutes(lockMinutes);
        this.unverifiedRetention = unverifiedRetention;
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
        Instant now = Instant.now();
        if (user.getLockedUntil() != null && now.isBefore(user.getLockedUntil())) {
            // Locked: no password is checked, but the time spent is the same.
            PasswordHashing.simulateCheck(password);
            return false;
        }
        boolean ok = PasswordHashing.matches(password, user.getPasswordHash());
        if (!ok) {
            recordFailure(user, now);
            return false;
        }
        if (user.getFailedLoginCount() != 0 || user.getLockedUntil() != null) {
            users.clearFailedLogins(user.getId());
        }
        if (PasswordHashing.needsRehash(user.getPasswordHash())) {
            rehash(user, password);
        }
        return true;
    }

    private void recordFailure(UserEntity user, Instant now) {
        users.incrementFailedLogins(user.getId());
        if (users.lockIfTooManyFailures(user.getId(), maxFailures, now.plus(lockDuration)) > 0) {
            log.warn("Account '{}' locked for {} minutes after {} failed sign-ins",
                    user.getUsername(), lockDuration.toMinutes(), maxFailures);
        }
    }

    /**
     * Replaces a hash made with an older, cheaper cost by a cost-12 one,
     * now that the plain password is known to be right. A failure here never
     * fails the login; the old hash simply stays.
     */
    private void rehash(UserEntity user, String password) {
        try {
            // Reload: the failure counters may have been reset by an update
            // query, and saving the stale copy would write them back.
            UserEntity fresh = users.findById(user.getId()).orElse(user);
            fresh.setPasswordHash(PasswordHashing.hash(password));
            users.save(fresh);
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

    /**
     * Database accounts must have a verified address before they may sign
     * (security Phase D decision). An unverified account can sign in and see
     * its own page; it cannot commit its name to a document.
     */
    @Override
    public boolean maySign(String username) {
        if (username == null) {
            return false;
        }
        return users.findByUsername(username.trim())
                .map(user -> user.isActive() && user.getEmailVerifiedAt() != null)
                .orElse(false);
    }

    /**
     * Security Phase D1 risk D7: a token issued before the account's password
     * changed is refused, which is what finally ends an access token on a
     * reset. {@code password_changed_at} has been written by
     * {@code UserEntity.changePasswordHash} since Phase D1 — both the reset
     * and the change path go through it — and nothing else writes it. In
     * particular {@link #rehash} deliberately does not: upgrading a bcrypt
     * cost is not a password change, and stamping it there would sign an
     * account out of its other sessions every time it signed in.
     *
     * <p>An account with no recorded change — every account before this
     * phase, and every account that has never changed its password — answers
     * {@code false}, so no existing token is invalidated by deploying this.
     */
    @Override
    public boolean passwordChangedSince(String username, Instant tokenIssuedAt) {
        if (username == null || tokenIssuedAt == null) {
            return false;
        }
        return users.findByUsername(username.trim())
                .map(UserEntity::getPasswordChangedAt)
                .map(changedAt -> changedAt.truncatedTo(ChronoUnit.SECONDS)
                        .isAfter(tokenIssuedAt))
                .orElse(false);
    }

    /**
     * Daily: deletes accounts created by website sign-up that never proved
     * their e-mail address and were never used (security Phase D1 risk D11).
     *
     * <p>The guards are in {@link UserRepository#deleteNeverVerifiedBefore},
     * spelled out in SQL because this is the only statement in the
     * application that removes a {@code users} row. In particular it never
     * touches an invitation identity ({@code PENDING_INVITE}), an account an
     * operator disabled, one that has ever been signed in to, or one that
     * holds a signer slot, owns a workflow or holds a claimed turn.
     *
     * <p>Set {@code SIGNER_WEB_UNVERIFIED_RETENTION} to zero to switch the
     * sweep off and keep such rows for ever: deleting accounts is the one
     * destructive piece of housekeeping here, so it has an off switch, unlike
     * the token sweeps.
     */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 1_800_000)
    @Transactional
    public void deleteNeverVerifiedAccounts() {
        if (unverifiedRetention == null || unverifiedRetention.isZero()
                || unverifiedRetention.isNegative()) {
            return; // switched off on purpose
        }
        int removed = users.deleteNeverVerifiedBefore(Instant.now().minus(unverifiedRetention));
        if (removed > 0) {
            // A count, never a username or an address.
            log.info("Deleted {} account(s) that never verified an e-mail address"
                    + " and were never used", removed);
        }
    }

    /**
     * Username, e-mail and whether that address is verified — nothing else.
     * Deliberately not the role, the status, the lockout counters or the last
     * login: the website has no use for them yet, and the smallest answer is
     * the one that cannot leak.
     */
    @Override
    public AccountProfile profile(String username) {
        if (username == null) {
            return new AccountProfile(null, null, false);
        }
        String id = username.trim();
        return users.findByUsername(id)
                .map(user -> new AccountProfile(user.getUsername(), user.getEmail(),
                        user.getEmailVerifiedAt() != null))
                .orElseGet(() -> new AccountProfile(id, null, false));
    }
}

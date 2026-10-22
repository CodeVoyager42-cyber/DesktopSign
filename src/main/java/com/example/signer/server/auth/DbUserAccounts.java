package com.example.signer.server.auth;

import com.example.signer.server.db.entity.LoginFailureEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.LoginFailureRepository;
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

    /**
     * Failures against one account from <b>all</b> addresses together before
     * the account itself is locked (risk L3). Ten times the per-address
     * figure: high enough that locking someone out is no longer five cheap
     * requests, low enough to still cap guessing spread over many addresses.
     */
    public static final int DEFAULT_ACCOUNT_MAX_FAILURES = 50;

    /** How long an unverified, unused account is kept (risk D11). */
    public static final Duration DEFAULT_UNVERIFIED_RETENTION = Duration.ofDays(7);

    /**
     * How long a pair row is kept after its last failure. Far longer than any
     * lock, so this is housekeeping and can never be what forgives a lock.
     */
    static final Duration FAILURE_RETENTION = Duration.ofDays(1);

    /** Length of {@code login_failures.client_address} (V13). */
    private static final int MAX_ADDRESS_LENGTH = 64;

    /**
     * Ceiling on the progressive delay (risk L3). A day, because beyond that
     * the escalation stops buying anything an attacker would not simply wait
     * out, and an address that has been wrong this many times in a row is
     * not a person who will come back tomorrow having remembered.
     *
     * <p><b>It must not exceed {@link #FAILURE_RETENTION}</b>, and is equal
     * to it. The sweep forgets a pair a day after its last failure, and
     * forgetting a pair releases its lock — so a cap longer than the
     * retention would mean the longest locks were quietly cut short by
     * housekeeping. At equality the row is forgotten exactly as the lock
     * expires. A test pins the relation so that changing one of the two
     * cannot silently break the other.
     */
    static final Duration MAX_DELAY = Duration.ofHours(24);

    /**
     * The pair key for an attempt that arrives with no address — a caller
     * that is not a request. The same sentinel {@code LoginRateLimitFilter}
     * uses, and deliberately a pair rather than an exemption: an
     * unidentified caller must not get the looser of the two limits.
     */
    static final String UNKNOWN_ADDRESS = "unknown";

    private final UserRepository users;
    private final LoginFailureRepository loginFailures;
    private final int maxFailures;
    private final int accountMaxFailures;
    private final Duration lockDuration;
    private final Duration unverifiedRetention;

    /** Default policy: 5 failures per address, 50 per account, 15 minutes. */
    public DbUserAccounts(UserRepository users, LoginFailureRepository loginFailures) {
        this(users, loginFailures, DEFAULT_MAX_FAILURES, DEFAULT_ACCOUNT_MAX_FAILURES,
                DEFAULT_LOCK.toMinutes(), DEFAULT_UNVERIFIED_RETENTION);
    }

    /** Chosen lockout policy, with the default unverified-account retention. */
    public DbUserAccounts(UserRepository users, LoginFailureRepository loginFailures,
                          int maxFailures, int accountMaxFailures, long lockMinutes) {
        this(users, loginFailures, maxFailures, accountMaxFailures, lockMinutes,
                DEFAULT_UNVERIFIED_RETENTION);
    }

    @Autowired
    public DbUserAccounts(UserRepository users, LoginFailureRepository loginFailures,
            @Value("${signature.remote-server.login.max-failures:5}") int maxFailures,
            @Value("${signature.remote-server.login.account-max-failures:50}")
                    int accountMaxFailures,
            @Value("${signature.remote-server.login.lock-minutes:15}") long lockMinutes,
            @Value("${signature.remote-server.web.unverified-retention:P7D}")
                    Duration unverifiedRetention) {
        if (maxFailures < 1 || lockMinutes < 1) {
            throw new IllegalStateException(
                    "signature.remote-server.login.max-failures and lock-minutes must be >= 1");
        }
        if (accountMaxFailures < maxFailures) {
            // Otherwise the account-wide lock would trip first and the
            // per-address one could never fire, which is the configuration
            // risk L3 was about — silently back to locking the holder out.
            throw new IllegalStateException(
                    "signature.remote-server.login.account-max-failures (" + accountMaxFailures
                            + ") must be >= max-failures (" + maxFailures + "): the account-wide"
                            + " lock is a backstop for guessing spread over many addresses,"
                            + " not the first limit to fire");
        }
        this.users = users;
        this.loginFailures = loginFailures;
        this.maxFailures = maxFailures;
        this.accountMaxFailures = accountMaxFailures;
        this.lockDuration = Duration.ofMinutes(lockMinutes);
        this.unverifiedRetention = unverifiedRetention;
    }

    @Override
    public boolean matches(String usernameOrEmail, String password) {
        return matches(usernameOrEmail, password, null);
    }

    /**
     * Security Phase 2 risk L3: the five-failure lock is per (account,
     * address) pair, and the account-wide one is a backstop at
     * {@code account-max-failures}.
     *
     * <p>Both are checked before any password is, and both are counted on a
     * failure — so an attacker from one address locks out their own address
     * after five tries, exactly as they locked out the account before, while
     * the holder arriving from a different address is unaffected.
     */
    @Override
    public boolean matches(String usernameOrEmail, String password, String clientAddress) {
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
            // account exists or can sign in. Deliberately not counted
            // against the pair either: an unknown account has no row, and
            // creating one would be a way to find out that it has none.
            PasswordHashing.simulateCheck(password);
            return false;
        }
        Instant now = Instant.now();
        String address = addressKey(clientAddress);
        // One read, used for both the lock decision and the clean-up below.
        LoginFailureEntity pair = loginFailures
                .findByUsernameAndClientAddress(user.getUsername(), address)
                .orElse(null);
        if (lockedOut(pair, now) || lockedOut(user, now)) {
            // Locked: no password is checked, but the time spent is the same.
            PasswordHashing.simulateCheck(password);
            return false;
        }
        boolean ok = PasswordHashing.matches(password, user.getPasswordHash());
        if (!ok) {
            recordFailure(user, address, now, pair == null ? 0 : pair.getFailedCount());
            return false;
        }
        if (pair != null) {
            loginFailures.forget(user.getUsername(), address);
        }
        if (user.getFailedLoginCount() != 0 || user.getLockedUntil() != null) {
            users.clearFailedLogins(user.getId());
        }
        if (PasswordHashing.needsRehash(user.getPasswordHash())) {
            rehash(user, password);
        }
        return true;
    }

    private static boolean lockedOut(LoginFailureEntity pair, Instant now) {
        return pair != null && pair.getLockedUntil() != null
                && now.isBefore(pair.getLockedUntil());
    }

    private static boolean lockedOut(UserEntity user, Instant now) {
        return user.getLockedUntil() != null && now.isBefore(user.getLockedUntil());
    }

    /**
     * The pair key for an address. Truncated to what the column holds, which
     * is safe in a way the deny-list's token ids were not: two addresses
     * sharing a 64-character prefix would merely share one budget, which is
     * more restrictive and never less. No address a servlet container reports
     * is anywhere near that long — the longest textual IPv6 form is 45
     * characters.
     */
    static String addressKey(String clientAddress) {
        if (clientAddress == null || clientAddress.isBlank()) {
            return UNKNOWN_ADDRESS;
        }
        String trimmed = clientAddress.trim();
        return trimmed.length() <= MAX_ADDRESS_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_ADDRESS_LENGTH);
    }

    /**
     * How long this address is refused for that account after its
     * {@code consecutiveFailures}-th wrong password in a row (security Phase
     * 2 risk L3, the progressive delay).
     *
     * <pre>
     *   1–2  no delay          people mistype their own password
     *     3  2 s
     *     4  8 s
     *     5  lock-minutes      the lock this always had (15 min by default)
     *     6  twice that, and so on, doubling, capped at {@link #MAX_DELAY}
     * </pre>
     *
     * <p><b>It is a refusal, not a slow answer.</b> Making the request itself
     * take longer is the obvious reading of "progressive delay" and it would
     * be a mistake: holding a servlet worker thread for seconds hands an
     * attacker resource exhaustion for the price of opening connections. The
     * answer comes back immediately and says the attempt was refused.
     *
     * <p><b>What the doubling is for.</b> The count behind it survives the
     * lock, so this is the part that makes a repeat offender dearer over
     * time. Before, a lock reset the count: when it expired the same address
     * got another five fast attempts, for ever — five guesses per fifteen
     * minutes, indefinitely. Now the sixth failure costs thirty minutes, the
     * seventh an hour, and an attacker who keeps coming back is paying
     * exponentially for guesses they are not getting.
     *
     * <p>None of this touches the holder: the delay belongs to a pair, and
     * the holder's own address has no failures on it. The first two being
     * free is for them, not the attacker — two tries at 250 ms of bcrypt is
     * no brute-force budget.
     */
    Duration delayFor(int consecutiveFailures) {
        // The configured threshold decides first, and the short delays only
        // fill the room below it. Checking them first would mean a
        // deployment that set max-failures to 3 or 4 never reached its own
        // lock, because the second and eighth second would have answered for
        // it.
        if (consecutiveFailures >= maxFailures) {
            int doublings = consecutiveFailures - maxFailures;
            if (doublings >= 20) {
                return MAX_DELAY;   // guard before the shift, not after it
            }
            Duration escalated = lockDuration.multipliedBy(1L << doublings);
            return escalated.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : escalated;
        }
        if (consecutiveFailures <= 2) {
            return Duration.ZERO;
        }
        return consecutiveFailures == 3 ? Duration.ofSeconds(2) : Duration.ofSeconds(8);
    }

    /**
     * Counts the failure at both scopes. The order matters only for the log:
     * the pair lock is the one that will normally fire, and it is the one
     * worth seeing first.
     */
    private void recordFailure(UserEntity user, String address, Instant now,
                              int failuresBefore) {
        loginFailures.countFailure(user.getUsername(), address, now);
        Duration delay = delayFor(failuresBefore + 1);
        if (!delay.isZero()) {
            loginFailures.lockUntil(user.getUsername(), address, now.plus(delay));
            // The address beside the account: during an attack this is the
            // one line an operator needs, and the rate limiter already
            // reports addresses at this level.
            log.warn("Address {} refused for account '{}' for {}s after {} failed sign-ins"
                            + " in a row", address, user.getUsername(), delay.toSeconds(),
                    failuresBefore + 1);
        }
        users.incrementFailedLogins(user.getId());
        if (users.lockIfTooManyFailures(user.getId(), accountMaxFailures,
                now.plus(lockDuration)) > 0) {
            log.warn("Account '{}' locked for {} minutes after {} failed sign-ins in total:"
                            + " the per-address limit was spread across addresses",
                    user.getUsername(), lockDuration.toMinutes(), accountMaxFailures);
        }
    }

    /**
     * Hourly: forgets pair rows idle for more than {@link #FAILURE_RETENTION}.
     * A pair is otherwise cleared only by a successful sign-in from that
     * address, so an attacker who tries once and never returns would keep a
     * row — and the address in it — for ever. The retention is far longer
     * than any lock, so this can never be what forgives one.
     *
     * <p>Last of the staggered sweeps (initial delay 40 min), after the
     * deny-list at 35.
     */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 2_400_000)
    @Transactional
    public void deleteIdleLoginFailures() {
        int removed = loginFailures.deleteIdleBefore(Instant.now().minus(FAILURE_RETENTION));
        if (removed > 0) {
            // A count, never an address or a username.
            log.info("Forgot {} idle sign-in failure row(s)", removed);
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

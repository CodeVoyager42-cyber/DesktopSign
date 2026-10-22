package com.example.signer.server.auth;

import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.LoginFailureEntity;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.LoginFailureRepository;
import com.example.signer.server.db.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 1: sign-in outcomes of database accounts, without a database. */
class DbUserAccountsTests {

    private static final String PASSWORD = "password-123";
    private static final String HASH = PasswordHashing.hash(PASSWORD);

    private UserRepository repo;
    private LoginFailureRepository failures;
    private DbUserAccounts accounts;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        failures = mock(LoginFailureRepository.class);
        when(repo.findByUsername(anyString())).thenReturn(Optional.empty());
        when(repo.findByEmail(anyString())).thenReturn(Optional.empty());
        when(failures.findByUsernameAndClientAddress(anyString(), anyString()))
                .thenReturn(Optional.empty());
        accounts = new DbUserAccounts(repo, failures);
    }

    /** A (account, address) pair locked until {@code lockedUntil}. */
    private void lockedPair(String username, String address, Instant lockedUntil) {
        LoginFailureEntity pair = mock(LoginFailureEntity.class);
        when(pair.getLockedUntil()).thenReturn(lockedUntil);
        when(failures.findByUsernameAndClientAddress(username, address))
                .thenReturn(Optional.of(pair));
    }

    private UserEntity account(String username, String hash, AccountStatus status) {
        UserEntity user = new UserEntity(username, username + "@example.test", hash, username, status);
        when(repo.findByUsername(username)).thenReturn(Optional.of(user));
        when(repo.findByEmail(username + "@example.test")).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void activeAccountWithRightPasswordSignsIn() {
        account("alice", HASH, AccountStatus.ACTIVE);
        assertThat(accounts.matches("alice", PASSWORD)).isTrue();
        assertThat(accounts.matches("ALICE@Example.TEST", PASSWORD)).isTrue();
        verify(repo, never()).save(any());   // cost-12 hash: nothing to upgrade
    }

    @Test
    void wrongPasswordUnknownUserDisabledAndPendingAllAnswerFalse() {
        account("alice", HASH, AccountStatus.ACTIVE);
        account("dora", HASH, AccountStatus.DISABLED);
        account("pete", null, AccountStatus.PENDING_INVITE);
        assertThat(accounts.matches("alice", "wrong-password")).isFalse();
        assertThat(accounts.matches("nobody", PASSWORD)).isFalse();
        assertThat(accounts.matches("dora", PASSWORD)).isFalse();
        assertThat(accounts.matches("pete", PASSWORD)).isFalse();
        assertThat(accounts.matches("pete", "")).isFalse();
        assertThat(accounts.matches(null, PASSWORD)).isFalse();
        assertThat(accounts.matches("alice", null)).isFalse();
    }

    @Test
    void legacyLowCostHashIsUpgradedAfterSuccessfulLogin() {
        String legacy = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(10));
        UserEntity user = account("carol", legacy, AccountStatus.ACTIVE);
        Instant changedBefore = user.getPasswordChangedAt();

        assertThat(accounts.matches("carol", PASSWORD)).isTrue();

        verify(repo).save(user);
        assertThat(user.getPasswordHash()).startsWith("$2a$12$").isNotEqualTo(legacy);
        assertThat(PasswordHashing.matches(PASSWORD, user.getPasswordHash())).isTrue();
        // A rehash is not a password change.
        assertThat(user.getPasswordChangedAt()).isEqualTo(changedBefore);
    }

    @Test
    void failedLoginNeverUpgradesTheHash() {
        String legacy = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(10));
        UserEntity user = account("carol", legacy, AccountStatus.ACTIVE);
        assertThat(accounts.matches("carol", "wrong-password")).isFalse();
        verify(repo, never()).save(any());
        assertThat(user.getPasswordHash()).isEqualTo(legacy);
    }

    /**
     * Unknown, disabled and pending accounts must cost the same bcrypt work
     * as a wrong password, so response time does not reveal which accounts
     * exist. Before Phase 1 they returned in well under a millisecond.
     * Lenient bound (half the median) to stay stable on a busy machine.
     */
    @Test
    void unknownAndBlockedAccountsTakeAsLongAsAWrongPassword() {
        account("alice", HASH, AccountStatus.ACTIVE);
        account("dora", HASH, AccountStatus.DISABLED);
        accounts.matches("alice", "warm-up");
        long wrong = median(() -> accounts.matches("alice", "wrong-password"));
        long unknown = median(() -> accounts.matches("nobody", "wrong-password"));
        long disabled = median(() -> accounts.matches("dora", PASSWORD));
        assertThat(unknown).isGreaterThan(wrong / 2);
        assertThat(disabled).isGreaterThan(wrong / 2);
    }

    @Test
    void aLockedAccountRefusesEvenTheRightPasswordWithoutCountingIt() {
        UserEntity user = account("lena", HASH, AccountStatus.ACTIVE);
        user.setLockedUntil(Instant.now().plusSeconds(600));
        assertThat(accounts.matches("lena", PASSWORD)).isFalse();
        verify(repo, never()).incrementFailedLogins(any());
        verify(repo, never()).clearFailedLogins(any());
    }

    @Test
    void anExpiredLockNoLongerBlocks() {
        UserEntity user = account("lena", HASH, AccountStatus.ACTIVE);
        user.setLockedUntil(Instant.now().minusSeconds(1));
        assertThat(accounts.matches("lena", PASSWORD)).isTrue();
        verify(repo).clearFailedLogins(user.getId());
    }

    @Test
    void aWrongPasswordIsCountedAtBothScopes() {
        // Risk L3. The pair is what will normally lock; the account-wide
        // count still runs, but at ten times the threshold, so one address
        // can no longer lock the holder out with five requests.
        UserEntity user = account("mo", HASH, AccountStatus.ACTIVE);

        assertThat(accounts.matches("mo", "wrong-password", "10.0.0.1")).isFalse();

        verify(failures).countFailure(org.mockito.ArgumentMatchers.eq("mo"),
                org.mockito.ArgumentMatchers.eq("10.0.0.1"), any());
        verify(failures, never()).lockUntil(anyString(), anyString(), any());
        verify(repo).incrementFailedLogins(user.getId());
        verify(repo).lockIfTooManyFailures(org.mockito.ArgumentMatchers.eq(user.getId()),
                org.mockito.ArgumentMatchers.eq(DbUserAccounts.DEFAULT_ACCOUNT_MAX_FAILURES),
                any());
    }

    @Test
    void theDelayGrowsWithTheFailuresAndNeverPastItsCap() {
        // Risk L3, the progressive delay. The first two cost nothing because
        // people mistype their own password; after that an address pays
        // exponentially for guesses it is not getting.
        assertThat(accounts.delayFor(1)).isZero();
        assertThat(accounts.delayFor(2)).isZero();
        assertThat(accounts.delayFor(3)).isEqualTo(Duration.ofSeconds(2));
        assertThat(accounts.delayFor(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(accounts.delayFor(5))
                .as("the fifth is the lock this always had").isEqualTo(Duration.ofMinutes(15));
        assertThat(accounts.delayFor(6)).isEqualTo(Duration.ofMinutes(30));
        assertThat(accounts.delayFor(7)).isEqualTo(Duration.ofHours(1));
        assertThat(accounts.delayFor(8)).isEqualTo(Duration.ofHours(2));
        assertThat(accounts.delayFor(12)).isEqualTo(DbUserAccounts.MAX_DELAY);
        assertThat(accounts.delayFor(500))
                .as("no overflow however long an address keeps at it")
                .isEqualTo(DbUserAccounts.MAX_DELAY);
        assertThat(accounts.delayFor(Integer.MAX_VALUE)).isEqualTo(DbUserAccounts.MAX_DELAY);
    }

    @Test
    void theDelayCapNeverOutlivesTheRowThatCarriesIt() {
        // The sweep forgets a pair a day after its last failure, and
        // forgetting it releases the lock. A cap longer than the retention
        // would mean the longest locks were quietly cut short.
        assertThat(DbUserAccounts.MAX_DELAY)
                .isLessThanOrEqualTo(DbUserAccounts.FAILURE_RETENTION);
    }

    @Test
    void theDelayIsScaledToAChosenLockDurationNotToFifteenMinutes() {
        DbUserAccounts strict = new DbUserAccounts(repo, failures, 3, 50, 1);

        // max-failures 3 and a one-minute lock. The threshold decides first,
        // so the third failure gets the configured lock and the doubling
        // starts from it — the fixed 2 s and 8 s steps only ever fill the
        // room BELOW the threshold, and here there is none above the second.
        assertThat(strict.delayFor(2)).isZero();
        assertThat(strict.delayFor(3))
                .as("the operator asked for a lock at three, so three locks")
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(strict.delayFor(4)).isEqualTo(Duration.ofMinutes(2));
        assertThat(strict.delayFor(5)).isEqualTo(Duration.ofMinutes(4));

        // And a threshold above the fixed steps keeps them.
        DbUserAccounts lax = new DbUserAccounts(repo, failures, 8, 50, 15);
        assertThat(lax.delayFor(3)).isEqualTo(Duration.ofSeconds(2));
        assertThat(lax.delayFor(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(lax.delayFor(7))
                .as("still the short step: the lock is not due until eight")
                .isEqualTo(Duration.ofSeconds(8));
        assertThat(lax.delayFor(8)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theNthFailureSetsTheNthDelayFromTheCountAlreadyOnTheRow() {
        // The count behind the escalation is the one on the row, so a lock
        // that has expired does not hand back a fresh budget.
        account("mo", HASH, AccountStatus.ACTIVE);
        LoginFailureEntity pair = mock(LoginFailureEntity.class);
        when(pair.getFailedCount()).thenReturn(5);
        when(pair.getLockedUntil()).thenReturn(Instant.now().minusSeconds(1));
        when(failures.findByUsernameAndClientAddress("mo", "10.0.0.1"))
                .thenReturn(Optional.of(pair));

        assertThat(accounts.matches("mo", "wrong-password", "10.0.0.1")).isFalse();

        verify(failures).lockUntil(org.mockito.ArgumentMatchers.eq("mo"),
                org.mockito.ArgumentMatchers.eq("10.0.0.1"),
                org.mockito.ArgumentMatchers.argThat(until ->
                        until.isAfter(Instant.now().plus(Duration.ofMinutes(29)))));
    }

    @Test
    void aLockedAddressIsRefusedTheRightPasswordAndAnotherAddressIsNot() {
        // The whole of risk L3: the attacker's address is locked out, and the
        // holder arriving from their own address signs in.
        account("nina", HASH, AccountStatus.ACTIVE);
        lockedPair("nina", "10.0.0.66", Instant.now().plusSeconds(600));

        assertThat(accounts.matches("nina", PASSWORD, "10.0.0.66"))
                .as("the address that spent its five attempts").isFalse();
        assertThat(accounts.matches("nina", PASSWORD, "192.168.1.5"))
                .as("the holder, from somewhere else, is unaffected").isTrue();

        // And being refused did not count against the account either.
        verify(repo, never()).incrementFailedLogins(any());
    }

    @Test
    void anExpiredPairLockNoLongerBlocks() {
        account("nina", HASH, AccountStatus.ACTIVE);
        lockedPair("nina", "10.0.0.66", Instant.now().minusSeconds(1));

        assertThat(accounts.matches("nina", PASSWORD, "10.0.0.66")).isTrue();
        verify(failures).forget("nina", "10.0.0.66");
    }

    @Test
    void anAttemptWithNoAddressCountsAgainstOneSharedPair() {
        // Not an exemption: a caller that is not a request must not get the
        // looser of the two limits.
        UserEntity user = account("mo", HASH, AccountStatus.ACTIVE);

        assertThat(accounts.matches("mo", "wrong-password")).isFalse();

        verify(failures).countFailure(org.mockito.ArgumentMatchers.eq("mo"),
                org.mockito.ArgumentMatchers.eq(DbUserAccounts.UNKNOWN_ADDRESS), any());
        verify(repo).incrementFailedLogins(user.getId());
    }

    @Test
    void anAddressLongerThanTheColumnIsTruncatedRatherThanDropped() {
        // Truncation is safe here and would not have been for a token id:
        // two addresses sharing a 64-character prefix share one budget,
        // which is more restrictive, never less. No real address is close.
        String absurd = "2001:db8:" + "0:".repeat(60) + "1";

        assertThat(DbUserAccounts.addressKey(absurd))
                .hasSize(64)
                .isEqualTo(absurd.substring(0, 64));
        assertThat(DbUserAccounts.addressKey("  10.0.0.1  ")).isEqualTo("10.0.0.1");
        assertThat(DbUserAccounts.addressKey("   ")).isEqualTo(DbUserAccounts.UNKNOWN_ADDRESS);
        assertThat(DbUserAccounts.addressKey(null)).isEqualTo(DbUserAccounts.UNKNOWN_ADDRESS);
    }

    @Test
    void successClearsEarlierFailuresOnlyWhenThereWereAny() {
        UserEntity clean = account("nia", HASH, AccountStatus.ACTIVE);
        assertThat(accounts.matches("nia", PASSWORD)).isTrue();
        verify(repo, never()).clearFailedLogins(any());

        UserEntity failed = account("omar", HASH, AccountStatus.ACTIVE);
        failed.setFailedLoginCount(3);
        assertThat(accounts.matches("omar", PASSWORD)).isTrue();
        verify(repo).clearFailedLogins(failed.getId());
    }

    @Test
    void unknownAndBlockedAccountsAreNeverCounted() {
        account("pete", null, AccountStatus.PENDING_INVITE);
        account("dora", HASH, AccountStatus.DISABLED);
        accounts.matches("nobody", "x");
        accounts.matches("pete", "x");
        accounts.matches("dora", "x");
        verify(repo, never()).incrementFailedLogins(any());
    }

    @Test
    void aTokenIssuedBeforeThePasswordChangedIsRejected() {
        // Risk D7. The account is unchanged in every other way: this is the
        // only thing that refuses the token.
        UserEntity user = account("ada", HASH, AccountStatus.ACTIVE);
        Instant changedAt = Instant.parse("2026-10-22T12:00:00Z");
        user.changePasswordHash(HASH, changedAt);

        assertThat(accounts.passwordChangedSince("ada", changedAt.minusSeconds(1)))
                .as("issued a second before the change").isTrue();
        assertThat(accounts.passwordChangedSince("ada", changedAt.plusSeconds(1)))
                .as("issued a second after it").isFalse();
    }

    @Test
    void aTokenIssuedInTheSameSecondAsTheChangeStands() {
        // iat is whole seconds, password_changed_at is microseconds, so a
        // sign-in in the same second as the change would otherwise be
        // refused its brand new token — and "reset, then sign in again" is
        // the path this feature exists to serve. The cost is a sub-second
        // window; the alternative breaks the common case.
        UserEntity user = account("grace", HASH, AccountStatus.ACTIVE);
        Instant changedAt = Instant.parse("2026-10-22T12:00:00.500Z");
        user.changePasswordHash(HASH, changedAt);

        assertThat(accounts.passwordChangedSince("grace",
                Instant.parse("2026-10-22T12:00:00Z")))
                .as("the token's own second, truncated as the JWT carries it").isFalse();
        assertThat(accounts.passwordChangedSince("grace",
                Instant.parse("2026-10-22T11:59:59Z")))
                .as("the second before is still caught").isTrue();
    }

    @Test
    void anAccountThatNeverChangedItsPasswordInvalidatesNothing() {
        // Every account that existed before this phase has a null column, so
        // deploying the check must not sign anyone out.
        account("hopper", HASH, AccountStatus.ACTIVE);

        assertThat(accounts.passwordChangedSince("hopper", Instant.now())).isFalse();
        assertThat(accounts.passwordChangedSince("hopper",
                Instant.parse("2000-01-01T00:00:00Z"))).isFalse();
    }

    @Test
    void anUnknownAccountOrAMissingIssuedAtAnswersFalse() {
        // The caller has already refused an unknown account by this point,
        // and this question is not the place to invent a second answer to
        // it. A token with no iat never reaches here: JwtService's lifetime
        // validator requires one.
        UserEntity user = account("ada", HASH, AccountStatus.ACTIVE);
        user.changePasswordHash(HASH, Instant.parse("2026-10-22T12:00:00Z"));

        assertThat(accounts.passwordChangedSince("nobody", Instant.now())).isFalse();
        assertThat(accounts.passwordChangedSince(null, Instant.now())).isFalse();
        assertThat(accounts.passwordChangedSince("ada", null)).isFalse();
    }

    @Test
    void invalidPolicyValuesAreRefusedAtStartup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new DbUserAccounts(repo, failures, 0, 50, 15))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new DbUserAccounts(repo, failures, 5, 50, 0))
                .isInstanceOf(IllegalStateException.class);
        // Risk L3: an account-wide threshold below the per-address one would
        // fire first, so the per-address lock could never fire and the
        // holder would be lockable again — silently, which is worse.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new DbUserAccounts(repo, failures, 5, 4, 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account-max-failures");
        // Equal is allowed: it is the old behaviour, chosen explicitly.
        new DbUserAccounts(repo, failures, 5, 5, 15);
    }

    private static long median(Runnable check) {
        long[] t = new long[5];
        for (int i = 0; i < t.length; i++) {
            long start = System.nanoTime();
            check.run();
            t[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(t);
        return t[2];
    }
}

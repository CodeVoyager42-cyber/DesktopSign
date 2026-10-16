package com.example.signer.server.auth;

import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCrypt;

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
    private DbUserAccounts accounts;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        when(repo.findByUsername(anyString())).thenReturn(Optional.empty());
        when(repo.findByEmail(anyString())).thenReturn(Optional.empty());
        accounts = new DbUserAccounts(repo);
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

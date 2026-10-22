package com.example.signer.server.auth;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.RefreshTokenRepository;
import com.example.signer.server.db.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 2 against a real PostgreSQL: account lockout and
 * database refresh tokens. Uses its own throwaway database
 * ({@value #DB}), recreated for each run. Skipped when PostgreSQL is not
 * reachable.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-auth-live-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + AuthenticationPersistenceLiveTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.login.max-failures=5",
                // The account-wide backstop (risk L3), lowered from its
                // default of 50 so that the test which has to reach it costs
                // ten bcrypt checks instead of fifty. The per-address limit
                // under test is the default 5.
                "signature.remote-server.login.account-max-failures=10",
                "signature.remote-server.login.lock-minutes=15"
        })
@EnabledIf(value = "com.example.signer.server.auth.AuthenticationPersistenceLiveTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class AuthenticationPersistenceLiveTests {

    static final String DB = "signflow_auth_live_test";

    private static final String PASSWORD = "correct-password-1";

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private RefreshTokenStore refreshTokens;

    @Autowired
    private RefreshTokenRepository refreshRepository;

    @Autowired
    private UserRepository users;

    @Autowired
    private com.example.signer.server.db.repository.LoginFailureRepository failures;

    /** The address doing the guessing, and the holder's own. */
    private static final String ATTACKER = "203.0.113.7";
    private static final String HOLDER = "198.51.100.4";

    /** Recreates the throwaway database; false (test skipped) when PostgreSQL is unreachable. */
    public static boolean freshDatabase() {
        String url = System.getenv().getOrDefault("SIGNER_DB_URL",
                "jdbc:postgresql://localhost:5432/postgres");
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + DB);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String newAccount() {
        String name = "u" + UUID.randomUUID().toString().substring(0, 8);
        UserEntity user = new UserEntity(name, name + "@example.test",
                PasswordHashing.hash(PASSWORD), name, AccountStatus.ACTIVE);
        users.save(user);
        return name;
    }

    private UserEntity reload(String name) {
        return users.findByUsername(name).orElseThrow();
    }

    private com.example.signer.server.db.entity.LoginFailureEntity pair(String name,
                                                                       String address) {
        return failures.findByUsernameAndClientAddress(name, address).orElseThrow();
    }

    /** However long the pair's current delay is, later. */
    private void expirePairLock(String name, String address) {
        failures.lockUntil(name, address, Instant.now().minusSeconds(1));
    }

    /**
     * Steps over the progressive delay without waiting it out, for the tests
     * that are about the count rather than the delay. Expiring the lock is
     * exactly what an attacker returning later would get — and deliberately
     * does not reset the count, so the escalation still applies.
     */
    private void asIfTheyWaited(String name, String address) {
        expirePairLock(name, address);
    }

    /* --------------------------- lockout ------------------------------ */

    @Test
    void databaseModeUsesTheDatabaseImplementations() {
        assertThat(accounts).isInstanceOf(DbUserAccounts.class);
        assertThat(refreshTokens).isInstanceOf(DbRefreshTokenStore.class);
    }

    @Test
    void fiveWrongPasswordsLockThatAddressOutAndLeaveTheHolderAlone() {
        // Risk L3, and the behaviour this test used to assert the other way
        // round: the fifth failure locked the ACCOUNT, so five cheap requests
        // locked the holder out of their own account. It now locks the
        // address that sent them.
        String name = newAccount();
        for (int i = 0; i < 4; i++) {
            assertThat(accounts.matches(name, "wrong-" + i, ATTACKER)).isFalse();
            // The third and fourth failures each set a short delay; this test
            // is about the fifth being a lock, so it steps over them.
            asIfTheyWaited(name, ATTACKER);
        }
        assertThat(pair(name, ATTACKER).getFailedCount()).isEqualTo(4);

        assertThat(accounts.matches(name, "wrong-5", ATTACKER)).isFalse();
        var locked = pair(name, ATTACKER);
        assertThat(locked.getLockedUntil()).isAfter(Instant.now().plusSeconds(14 * 60));
        assertThat(locked.getFailedCount())
                .as("the count survives the lock, so the next one is longer").isEqualTo(5);

        // Locked for that address: the right password is refused too, and the
        // refusal is not counted — against the pair or the account.
        assertThat(accounts.matches(name, PASSWORD, ATTACKER)).isFalse();
        assertThat(accounts.matches(name + "@example.test", PASSWORD, ATTACKER)).isFalse();
        assertThat(pair(name, ATTACKER).getFailedCount())
                .as("a refusal is not a failure, so the lock cannot extend itself")
                .isEqualTo(5);
        assertThat(reload(name).getFailedLoginCount())
                .as("five failures, and the two refusals after them were not counted")
                .isEqualTo(5);

        // The point of the whole change: the account is NOT locked, so the
        // holder signs in from their own address.
        assertThat(reload(name).getLockedUntil()).isNull();
        assertThat(accounts.matches(name, PASSWORD, HOLDER))
                .as("the holder is unaffected by someone else's failures").isTrue();
    }

    @Test
    void comingBackAfterALockCostsTwiceAsLongTheNextTime() {
        // The progressive delay, and the hole it closes: a lock used to reset
        // the count, so an attacker who waited it out got another five fast
        // attempts, for ever — five guesses every fifteen minutes,
        // indefinitely. The count now survives, so the sixth failure is
        // thirty minutes and the seventh an hour.
        String name = newAccount();
        for (int i = 0; i < 5; i++) {
            accounts.matches(name, "wrong-" + i, ATTACKER);
            asIfTheyWaited(name, ATTACKER);
        }
        assertThat(pair(name, ATTACKER).getFailedCount()).isEqualTo(5);

        accounts.matches(name, "wrong-6", ATTACKER);
        assertThat(pair(name, ATTACKER).getLockedUntil())
                .as("the sixth failure is worth half an hour, not another fifteen minutes")
                .isAfter(Instant.now().plusSeconds(29 * 60));

        asIfTheyWaited(name, ATTACKER);
        accounts.matches(name, "wrong-7", ATTACKER);
        assertThat(pair(name, ATTACKER).getLockedUntil())
                .as("and the seventh an hour").isAfter(Instant.now().plusSeconds(59 * 60));
        assertThat(pair(name, ATTACKER).getFailedCount()).isEqualTo(7);

        // The holder is still unaffected by any of it.
        assertThat(accounts.matches(name, PASSWORD, HOLDER)).isTrue();
    }

    @Test
    void theAddressLockEndsOnItsOwnAndASuccessForgetsThePair() {
        String name = newAccount();
        for (int i = 0; i < 5; i++) {
            accounts.matches(name, "wrong", ATTACKER);
            asIfTheyWaited(name, ATTACKER);
        }
        // Back under lock after the fifth, as the test above establishes.
        accounts.matches(name, "wrong-again", ATTACKER);
        assertThat(accounts.matches(name, PASSWORD, ATTACKER)).isFalse();

        expirePairLock(name, ATTACKER);

        assertThat(accounts.matches(name, PASSWORD, ATTACKER)).isTrue();
        assertThat(failures.findByUsernameAndClientAddress(name, ATTACKER))
                .as("a success forgets the pair rather than zeroing it").isEmpty();
        assertThat(reload(name).getFailedLoginCount()).isZero();
    }

    @Test
    void enoughFailuresAcrossEnoughAddressesStillLockTheAccountItself() {
        // The backstop. Without it the per-address lock would be a way to
        // guess for ever by rotating addresses, which is cheap with IPv6.
        // Ten here (see the properties above); fifty by default.
        String name = newAccount();
        for (int a = 0; a < 2; a++) {
            for (int i = 0; i < 5; i++) {
                assertThat(accounts.matches(name, "wrong", "10.1.1." + a)).isFalse();
                asIfTheyWaited(name, "10.1.1." + a);
            }
        }

        assertThat(reload(name).getLockedUntil())
                .as("ten failures in total, whatever addresses they came from")
                .isAfter(Instant.now().plusSeconds(14 * 60));
        assertThat(accounts.matches(name, PASSWORD, "10.9.9.9"))
                .as("and now even an address with no failures of its own is refused")
                .isFalse();
    }

    @Test
    void twoMistypesCostNothingAndASuccessClearsThemAndTheEscalation() {
        String name = newAccount();
        accounts.matches(name, "wrong-1", HOLDER);
        accounts.matches(name, "wrong-2", HOLDER);
        assertThat(reload(name).getFailedLoginCount()).isEqualTo(2);

        // The holder's own case, and why the first two failures carry no
        // delay: they get the password right on the third try, with nothing
        // in the way.
        assertThat(accounts.matches(name, PASSWORD, HOLDER)).isTrue();
        assertThat(reload(name).getFailedLoginCount()).isZero();
        assertThat(failures.findByUsernameAndClientAddress(name, HOLDER))
                .as("and the pair is forgotten, so the escalation starts over").isEmpty();

        // Four more failures are therefore the first to fourth again, not
        // the third to sixth: no lock.
        for (int i = 0; i < 4; i++) {
            accounts.matches(name, "wrong", HOLDER);
            asIfTheyWaited(name, HOLDER);
        }
        assertThat(pair(name, HOLDER).getFailedCount()).isEqualTo(4);
        assertThat(reload(name).getLockedUntil()).isNull();
        assertThat(accounts.matches(name, PASSWORD, HOLDER)).isTrue();
    }

    @Test
    void parallelWrongPasswordsAreAllCountedAtBothScopes() throws Exception {
        String name = newAccount();
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            attempts.add(() -> accounts.matches(name, "wrong", ATTACKER));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (Future<Boolean> f : pool.invokeAll(attempts)) {
                assertThat(f.get()).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(reload(name).getFailedLoginCount()).isEqualTo(4);
        // Four simultaneous first-failures for one pair: the upsert has to
        // insert once and increment three times, not race four inserts into
        // the UNIQUE constraint or lose an increment.
        assertThat(pair(name, ATTACKER).getFailedCount()).isEqualTo(4);
    }

    /* ------------------------ refresh tokens -------------------------- */

    @Test
    void onlyTheHashIsStored() {
        String name = newAccount();
        String token = refreshTokens.issue(name);
        assertThat(refreshRepository.findAll())
                .noneMatch(t -> t.getTokenHash().equals(token))
                .anyMatch(t -> t.getTokenHash().equals(RefreshTokenStore.hash(token))
                        && t.getUsername().equals(name));
    }

    @Test
    void rotationReuseDetectionAndLogout() {
        String name = newAccount();
        String first = refreshTokens.issue(name);

        var rotated = refreshTokens.refresh(first);
        assertThat(rotated).isInstanceOf(RefreshTokenStore.RefreshResult.Accepted.class);
        String second = ((RefreshTokenStore.RefreshResult.Accepted) rotated).newRefreshToken();
        assertThat(refreshTokens.isUsable(first)).isFalse();
        assertThat(refreshTokens.isUsable(second)).isTrue();

        // Presenting the replaced token again kills the whole family.
        assertThat(refreshTokens.refresh(first))
                .isInstanceOf(RefreshTokenStore.RefreshResult.ReusedDetected.class);
        assertThat(refreshTokens.isUsable(second)).isFalse();
        assertThat(refreshTokens.refresh(second))
                .isInstanceOf(RefreshTokenStore.RefreshResult.Invalid.class);

        // Logout of a fresh sign-in revokes it; a second logout is harmless.
        String other = refreshTokens.issue(name);
        assertThat(refreshTokens.revoke(other)).isTrue();
        assertThat(refreshTokens.revoke(other)).isFalse();
        assertThat(refreshTokens.refresh(other))
                .isInstanceOf(RefreshTokenStore.RefreshResult.Invalid.class);
    }

    @Test
    void sessionsSurviveAServerRestart() {
        String name = newAccount();
        String token = refreshTokens.issue(name);
        // A new store over the same database = the server after a restart.
        DbRefreshTokenStore afterRestart = new DbRefreshTokenStore(refreshRepository, 12);
        assertThat(afterRestart.isUsable(token)).isTrue();
    }

    @Test
    void twoSimultaneousRefreshesOfOneTokenCannotBothSucceed() throws Exception {
        String name = newAccount();
        String token = refreshTokens.issue(name);
        CountDownLatch start = new CountDownLatch(1);
        Callable<RefreshTokenStore.RefreshResult> attempt = () -> {
            start.await();
            return refreshTokens.refresh(token);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RefreshTokenStore.RefreshResult> a = pool.submit(attempt);
            Future<RefreshTokenStore.RefreshResult> b = pool.submit(attempt);
            start.countDown();
            long accepted = List.of(a.get(), b.get()).stream()
                    .filter(r -> r instanceof RefreshTokenStore.RefreshResult.Accepted)
                    .count();
            assertThat(accepted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void disablingRevokesEverySessionOfTheAccount() {
        String name = newAccount();
        String a = refreshTokens.issue(name);
        String b = refreshTokens.issue(name);
        refreshTokens.revokeAllForUser(name);
        assertThat(refreshTokens.isUsable(a)).isFalse();
        assertThat(refreshTokens.isUsable(b)).isFalse();
    }
}

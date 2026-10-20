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

    /* --------------------------- lockout ------------------------------ */

    @Test
    void databaseModeUsesTheDatabaseImplementations() {
        assertThat(accounts).isInstanceOf(DbUserAccounts.class);
        assertThat(refreshTokens).isInstanceOf(DbRefreshTokenStore.class);
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenAgainstTheRightOne() {
        String name = newAccount();
        for (int i = 0; i < 4; i++) {
            assertThat(accounts.matches(name, "wrong-" + i)).isFalse();
        }
        assertThat(reload(name).getFailedLoginCount()).isEqualTo(4);
        assertThat(reload(name).getLockedUntil()).isNull();

        assertThat(accounts.matches(name, "wrong-5")).isFalse();
        UserEntity locked = reload(name);
        assertThat(locked.getLockedUntil()).isAfter(Instant.now().plusSeconds(14 * 60));
        assertThat(locked.getFailedLoginCount()).isZero();

        // Locked: the right password is refused too, and it is not counted.
        assertThat(accounts.matches(name, PASSWORD)).isFalse();
        assertThat(accounts.matches(name + "@example.test", PASSWORD)).isFalse();
        assertThat(reload(name).getFailedLoginCount()).isZero();
    }

    @Test
    void theLockEndsOnItsOwnAndASuccessResetsTheCount() {
        String name = newAccount();
        for (int i = 0; i < 5; i++) {
            accounts.matches(name, "wrong");
        }
        UserEntity locked = reload(name);
        locked.setLockedUntil(Instant.now().minusSeconds(1));   // 15 minutes later
        users.save(locked);

        assertThat(accounts.matches(name, PASSWORD)).isTrue();
        assertThat(reload(name).getLockedUntil()).isNull();
        assertThat(reload(name).getFailedLoginCount()).isZero();
    }

    @Test
    void aSuccessBeforeTheLimitClearsEarlierFailures() {
        String name = newAccount();
        accounts.matches(name, "wrong-1");
        accounts.matches(name, "wrong-2");
        assertThat(accounts.matches(name, PASSWORD)).isTrue();
        assertThat(reload(name).getFailedLoginCount()).isZero();
        // So 4 more failures do not lock.
        for (int i = 0; i < 4; i++) {
            accounts.matches(name, "wrong");
        }
        assertThat(reload(name).getLockedUntil()).isNull();
        assertThat(accounts.matches(name, PASSWORD)).isTrue();
    }

    @Test
    void parallelWrongPasswordsAreAllCounted() throws Exception {
        String name = newAccount();
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            attempts.add(() -> accounts.matches(name, "wrong"));
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

package com.example.signer.server.auth;

import com.example.signer.server.WorkflowServerApplication;
import com.example.signer.server.db.entity.AccountStatus;
import com.example.signer.server.db.entity.UserEntity;
import com.example.signer.server.db.repository.LoginFailureRepository;
import com.example.signer.server.db.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 2 risk L3, the operator unlock: clearing every lockout on
 * one account.
 *
 * <p>It exists because the lock is now progressive — an address can be
 * refused for up to a day — so there has to be a way back that is not
 * waiting. {@link AccountUnlockRunnerLiveTests} covers the command-line
 * option that reaches this; here it is the operation itself.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-l3-unlock-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + AccountUnlockLiveTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                "signature.remote-server.login.max-failures=5",
                "signature.remote-server.login.lock-minutes=15"
        })
@EnabledIf(value = "com.example.signer.server.auth.AccountUnlockLiveTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class AccountUnlockLiveTests {

    static final String DB = "signflow_l3_unlock_test";

    private static final String PASSWORD = "correct-password-1";

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private UserRepository users;

    @Autowired
    private LoginFailureRepository failures;

    @Autowired
    private org.springframework.context.ApplicationContext context;

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

    /* ------------------------------------------------------------------ */

    @Test
    void unlockingClearsTheAccountLockAndEveryAddressLock() {
        String name = newAccount();
        lockOutFrom(name, "203.0.113.7");
        lockOutFrom(name, "203.0.113.8");
        lockTheAccount(name);

        // Both halves really are refusing before the unlock.
        assertThat(accounts.matches(name, PASSWORD, "203.0.113.7")).isFalse();
        assertThat(accounts.matches(name, PASSWORD, "10.0.0.1"))
                .as("the account-wide lock refuses even an address with no failures")
                .isFalse();

        UserAccountService.UnlockResult result = accounts.unlock(name);

        assertThat(result.accountKnown()).isTrue();
        assertThat(result.accountLockCleared()).isTrue();
        assertThat(result.lockedUntilBefore())
                .as("reported so an operator can see what they shortened").isNotNull();
        assertThat(result.addressLocksForgotten()).isEqualTo(2);
        assertThat(result.clearedAnything()).isTrue();

        // And the account is usable again from everywhere, at once.
        assertThat(accounts.matches(name, PASSWORD, "203.0.113.7")).isTrue();
        assertThat(accounts.matches(name, PASSWORD, "203.0.113.8")).isTrue();
        assertThat(reload(name).getLockedUntil()).isNull();
        assertThat(reload(name).getFailedLoginCount()).isZero();
        assertThat(failures.findByUsernameAndClientAddress(name, "203.0.113.7")).isEmpty();
    }

    @Test
    void unlockingGrantsNothingAndTheWrongPasswordIsStillWrong() {
        // The one thing an unlock must not be is a way in.
        String name = newAccount();
        lockOutFrom(name, "203.0.113.7");

        assertThat(accounts.unlock(name).clearedAnything()).isTrue();

        assertThat(accounts.matches(name, "still-not-the-password", "203.0.113.7"))
                .as("it removes refusals, it does not authenticate").isFalse();
        assertThat(accounts.matches(name, PASSWORD, "203.0.113.7")).isTrue();
    }

    @Test
    void unlockingAnAccountThatIsNotLockedClearsNothingAndIsNotAnError() {
        String name = newAccount();

        UserAccountService.UnlockResult result = accounts.unlock(name);

        assertThat(result.accountKnown()).isTrue();
        assertThat(result.clearedAnything()).isFalse();
        assertThat(result.lockedUntilBefore()).isNull();
        assertThat(result.addressLocksForgotten()).isZero();
        assertThat(accounts.matches(name, PASSWORD, "10.0.0.1")).isTrue();
    }

    @Test
    void unlockingIsIdempotent() {
        String name = newAccount();
        lockOutFrom(name, "203.0.113.7");

        assertThat(accounts.unlock(name).addressLocksForgotten()).isEqualTo(1);
        assertThat(accounts.unlock(name).clearedAnything())
                .as("the second time there is nothing left to clear").isFalse();
    }

    @Test
    void anUnknownAccountIsReportedAsUnknownRatherThanAsASuccessfulNoOp() {
        // An operator who mistypes a username must be told, not shown
        // something that reads like it worked.
        UserAccountService.UnlockResult result = accounts.unlock("nobody-here");

        assertThat(result.accountKnown()).isFalse();
        assertThat(result.clearedAnything()).isFalse();

        // Including the easiest mistake: the e-mail address, not the username.
        String name = newAccount();
        lockOutFrom(name, "203.0.113.7");
        assertThat(accounts.unlock(name + "@example.test").accountKnown()).isFalse();
        assertThat(failures.findByUsernameAndClientAddress(name, "203.0.113.7"))
                .as("and nothing was cleared for the account they meant").isPresent();
    }

    @Test
    void aServerWithoutTheOptionDoesNotCarryTheRunnerAtAll() {
        // The counterpart of AccountUnlockRunnerLiveTests: no
        // --signer.unlock-account here, so the start-up action does not exist
        // in this context. That is what keeps it out of normal deployments.
        assertThat(this.context.getBeanNamesForType(AccountUnlockRunner.class)).isEmpty();
    }

    @Test
    void aBlankOrAbsentNameUnlocksNothing() {
        assertThat(accounts.unlock(null).accountKnown()).isFalse();
        assertThat(accounts.unlock("  ").accountKnown()).isFalse();
        assertThat(accounts.unlock("").clearedAnything()).isFalse();
    }

    @Test
    void onlyOneAccountIsUnlocked() {
        String locked = newAccount();
        String alsoLocked = newAccount();
        lockOutFrom(locked, "203.0.113.7");
        lockOutFrom(alsoLocked, "203.0.113.7");

        accounts.unlock(locked);

        assertThat(accounts.matches(locked, PASSWORD, "203.0.113.7")).isTrue();
        assertThat(accounts.matches(alsoLocked, PASSWORD, "203.0.113.7"))
                .as("the same address is still locked out of the other account").isFalse();
    }

    /* ------------------------------ helpers ----------------------------- */

    private String newAccount() {
        String name = "u" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new UserEntity(name, name + "@example.test",
                PasswordHashing.hash(PASSWORD), name, AccountStatus.ACTIVE));
        return name;
    }

    private UserEntity reload(String name) {
        return users.findByUsername(name).orElseThrow();
    }

    /** Five failures' worth of lock, written rather than earned. */
    private void lockOutFrom(String name, String address) {
        failures.countFailure(name, address, Instant.now());
        failures.lockUntil(name, address, Instant.now().plus(Duration.ofHours(24)));
    }

    private void lockTheAccount(String name) {
        UserEntity user = reload(name);
        user.setLockedUntil(Instant.now().plus(Duration.ofMinutes(15)));
        user.setFailedLoginCount(50);
        users.save(user);
    }
}

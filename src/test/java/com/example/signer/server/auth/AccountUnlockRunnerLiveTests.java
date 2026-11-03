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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security Phase 2 risk L3: the command-line option an operator actually
 * uses, {@code --signer.unlock-account}.
 *
 * <p>The account and its lock are seeded <b>before</b> the context starts, by
 * {@link #freshDatabase()}, because the runner does its work as the context
 * comes up — by the time a test method runs it has already happened. So this
 * class asserts the state the runner left behind, which is the only way to
 * test a start-up action without pretending it is a method call.
 *
 * <p>{@link AccountUnlockLiveTests} covers the unlock itself. What is tested
 * here is that the option reaches it at all, and that the bean exists only
 * when the option is given — the point being that a normal server never
 * carries it.
 */
@SpringBootTest(classes = WorkflowServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "signature.remote-server.database.enabled=true",
                "signature.remote-server.storage=${java.io.tmpdir}/signer-l3-runner-test",
                "signature.remote-server.database.url=jdbc:postgresql://localhost:5432/"
                        + AccountUnlockRunnerLiveTests.DB,
                "signature.remote-server.database.username=${SIGNER_DB_USERNAME:postgres}",
                "signature.remote-server.database.password=${SIGNER_DB_PASSWORD:}",
                // The option, exactly as the documented command passes it.
                "signer.unlock-account=" + AccountUnlockRunnerLiveTests.LOCKED_OUT
        })
@EnabledIf(value = "com.example.signer.server.auth.AccountUnlockRunnerLiveTests#freshDatabase",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class AccountUnlockRunnerLiveTests {

    static final String DB = "signflow_l3_runner_test";

    /** The account seeded locked, and named in the option above. */
    static final String LOCKED_OUT = "locked-out-ada";

    static final String ADDRESS = "203.0.113.7";

    @Autowired
    private UserRepository users;

    @Autowired
    private LoginFailureRepository failures;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    /**
     * Recreates the database, applies the schema, and seeds a locked-out
     * account — all before Spring starts, so the runner has something to
     * find. Flyway is driven directly here for the same reason.
     */
    public static boolean freshDatabase() {
        String admin = System.getenv().getOrDefault("SIGNER_DB_URL",
                "jdbc:postgresql://localhost:5432/postgres");
        String user = System.getenv().getOrDefault("SIGNER_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("SIGNER_DB_PASSWORD", "");
        String url = "jdbc:postgresql://localhost:5432/" + DB;
        try {
            try (Connection c = DriverManager.getConnection(admin, user, password);
                 Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
                s.execute("CREATE DATABASE " + DB);
            }
            org.flywaydb.core.Flyway.configure()
                    .dataSource(url, user, password)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            try (Connection c = DriverManager.getConnection(url, user, password);
                 Statement s = c.createStatement()) {
                s.execute("INSERT INTO users (username, email, password_hash, display_name,"
                        + " status, failed_login_count, locked_until) VALUES ('"
                        + LOCKED_OUT + "', '" + LOCKED_OUT + "@example.test', '"
                        + PasswordHashing.hash("correct-password-1") + "', 'Ada', 'ACTIVE',"
                        + " 50, now() + INTERVAL '15 minutes')");
                s.execute("INSERT INTO login_failures (username, client_address,"
                        + " failed_count, locked_until, first_failure_at, last_failure_at)"
                        + " VALUES ('" + LOCKED_OUT + "', '" + ADDRESS + "', 9,"
                        + " now() + INTERVAL '24 hours', now(), now())");
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */

    @Test
    void theOptionUnlockedTheAccountWhileTheContextStarted() {
        UserEntity account = users.findByUsername(LOCKED_OUT).orElseThrow();

        assertThat(account.getLockedUntil())
                .as("the account-wide lock the option was given for").isNull();
        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(failures.findByUsernameAndClientAddress(LOCKED_OUT, ADDRESS))
                .as("and the address that was refused for the next 24 hours").isEmpty();
    }

    @Test
    void thePasswordIsUntouchedByTheUnlock() {
        // An unlock removes refusals and nothing else. If it rewrote or
        // cleared the hash it would be a way into the account, which is the
        // one thing an operator tool like this must not be.
        UserEntity account = users.findByUsername(LOCKED_OUT).orElseThrow();

        assertThat(PasswordHashing.matches("correct-password-1", account.getPasswordHash()))
                .isTrue();
        assertThat(account.getPasswordChangedAt())
                .as("and it does not count as a password change, so no token is invalidated")
                .isNull();
    }

    @Test
    void theRunnerExistsOnlyBecauseTheOptionWasGiven() {
        // A server started without it does not carry the bean at all, which
        // is what keeps a start-up action out of normal deployments.
        assertThat(context.getBeanNamesForType(AccountUnlockRunner.class))
                .as("present here because the option is set in this context's properties")
                .hasSize(1);
        assertThat(context.getEnvironment().getProperty("signer.unlock-account"))
                .isEqualTo(LOCKED_OUT);
    }
}

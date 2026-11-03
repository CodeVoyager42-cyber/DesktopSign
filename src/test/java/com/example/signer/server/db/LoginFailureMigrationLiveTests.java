package com.example.signer.server.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V13 (failed sign-ins per account and address, security Phase 2 risk L3)
 * against a real PostgreSQL, on a fresh database and on top of an existing
 * V12 one that already holds data. Own throwaway database ({@value #DB}).
 * Skipped when PostgreSQL is not reachable.
 *
 * <p>Same point as every migration test before it: V13 is <b>additive</b>. It
 * creates one table and one index, and in particular leaves
 * {@code users.failed_login_count} and {@code users.locked_until} (V5) alone —
 * the account-wide lock still works, it is just no longer the only one.
 */
@EnabledIf(value = "com.example.signer.server.db.AccountIdentityMigrationLiveTests#postgresReachable",
        disabledReason = "PostgreSQL not reachable — set SIGNER_DB_URL/SIGNER_DB_USERNAME/SIGNER_DB_PASSWORD")
class LoginFailureMigrationLiveTests {

    private static final String DB = "signflow_v13_migration_test";

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String adminUrl() {
        return env("SIGNER_DB_URL", "jdbc:postgresql://localhost:5432/postgres");
    }

    private static String testUrl() {
        String admin = adminUrl();
        return admin.substring(0, admin.lastIndexOf('/') + 1) + DB;
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, env("SIGNER_DB_USERNAME", "postgres"),
                env("SIGNER_DB_PASSWORD", ""));
    }

    private static void exec(String url, String sql) throws SQLException {
        try (Connection c = connect(url); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = connect(testUrl()); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(testUrl(), env("SIGNER_DB_USERNAME", "postgres"),
                        env("SIGNER_DB_PASSWORD", ""))
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target));
        }
        return config.load();
    }

    private static void account(String username) throws SQLException {
        exec(testUrl(), "INSERT INTO users (username, email, password_hash, display_name,"
                + " status) VALUES ('" + username + "', '" + username + "@example.test',"
                + " 'x', '" + username + "', 'ACTIVE')");
    }

    @BeforeEach
    void freshDatabase() throws SQLException {
        exec(adminUrl(), "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        exec(adminUrl(), "CREATE DATABASE " + DB);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        exec(adminUrl(), "DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
    }

    @Test
    void v13AppliesOnAFreshDatabaseAndCreatesTheFailureTable() throws SQLException {
        flyway(null).migrate();

        assertThat(one("SELECT count(*) FROM flyway_schema_history"
                + " WHERE version = '13' AND success")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM login_failures")).isEqualTo("0");
    }

    @Test
    void theColumnsAreTheOnesTheLockNeedsAndNothingElse() throws SQLException {
        flyway(null).migrate();

        assertThat(one("SELECT string_agg(column_name, ',' ORDER BY ordinal_position)"
                + " FROM information_schema.columns WHERE table_name = 'login_failures'"))
                .as("no column for the password, the identifier typed, or a user agent:"
                        + " the lock needs a pair, a count and two timestamps")
                .isEqualTo("id,username,client_address,failed_count,locked_until,"
                        + "first_failure_at,last_failure_at");
        assertThat(one("SELECT data_type FROM information_schema.columns"
                + " WHERE table_name = 'login_failures' AND column_name = 'locked_until'"))
                .isEqualTo("timestamp with time zone");
        assertThat(one("SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_name = 'login_failures' AND column_name = 'locked_until'"))
                .as("null until the pair has actually been locked").isEqualTo("YES");
        assertThat(one("SELECT character_maximum_length FROM information_schema.columns"
                + " WHERE table_name = 'login_failures' AND column_name = 'client_address'"))
                .as("the longest textual IPv6 form is 45 characters")
                .isEqualTo("64");
    }

    @Test
    void thePairIsUniqueSoOneAddressCannotHoldTwoBudgets() throws SQLException {
        flyway(null).migrate();
        account("ada");

        exec(testUrl(), "INSERT INTO login_failures (username, client_address,"
                + " failed_count, first_failure_at, last_failure_at)"
                + " VALUES ('ada', '10.0.0.1', 1, now(), now())");

        assertThatThrownBy(() -> exec(testUrl(), "INSERT INTO login_failures (username,"
                + " client_address, failed_count, first_failure_at, last_failure_at)"
                + " VALUES ('ada', '10.0.0.1', 1, now(), now())"))
                .as("which is why the store counts with ON CONFLICT ... DO UPDATE")
                .isInstanceOf(SQLException.class);

        // A different address for the same account is a different budget.
        exec(testUrl(), "INSERT INTO login_failures (username, client_address,"
                + " failed_count, first_failure_at, last_failure_at)"
                + " VALUES ('ada', '10.0.0.2', 1, now(), now())");
        assertThat(one("SELECT count(*) FROM login_failures WHERE username = 'ada'"))
                .isEqualTo("2");
    }

    @Test
    void failureRowsCascadeWithTheAccountAndHaveNoIndexOnTheAddressAlone()
            throws SQLException {
        flyway(null).migrate();
        account("ada");
        exec(testUrl(), "INSERT INTO login_failures (username, client_address,"
                + " failed_count, first_failure_at, last_failure_at)"
                + " VALUES ('ada', '10.0.0.1', 3, now(), now())");

        exec(testUrl(), "DELETE FROM users WHERE username = 'ada'");

        assertThat(one("SELECT count(*) FROM login_failures"))
                .as("an account's failure rows are its own, like its refresh tokens")
                .isEqualTo("0");
        assertThat(one("SELECT string_agg(indexname, ',' ORDER BY indexname)"
                + " FROM pg_indexes WHERE tablename = 'login_failures'"))
                .as("nothing asks what one address has been attacking, and an index"
                        + " would be an invitation to start")
                .isEqualTo("idx_login_failures_last_failure,login_failures_pkey,"
                        + "uq_login_failures_account_address");
    }

    @Test
    void v13LeavesAnExistingV12DatabaseAndItsAccountLockIntact() throws SQLException {
        flyway("12").migrate();
        account("ada");
        exec(testUrl(), "UPDATE users SET failed_login_count = 3,"
                + " locked_until = now() + INTERVAL '15 minutes' WHERE username = 'ada'");
        exec(testUrl(), "INSERT INTO denied_access_tokens (token_id, denied_at, expires_at)"
                + " VALUES ('jti-1', now(), now() + INTERVAL '15 minutes')");

        // Target V13: the count below is a claim about this migration's step.
        flyway("13").migrate();

        assertThat(one("SELECT failed_login_count FROM users WHERE username = 'ada'"))
                .as("the account-wide count survives: it is still the backstop")
                .isEqualTo("3");
        assertThat(one("SELECT count(*) FROM users WHERE username = 'ada'"
                + " AND locked_until IS NOT NULL"))
                .as("and so does a lock that was in force when V13 was applied")
                .isEqualTo("1");
        assertThat(one("SELECT count(*) FROM denied_access_tokens")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM login_failures")).isEqualTo("0");
        assertThat(one("SELECT count(*) FROM flyway_schema_history WHERE success"))
                .as("V13 added itself and re-ran nothing").isEqualTo("13");
    }
}
